/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.plan;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.no42.rrdlift.labels.LabelIndex;
import org.no42.rrdlift.labels.Sanitizers;
import org.no42.rrdlift.labels.SeriesKey;
import org.no42.rrdlift.repo.RepositoryWalker;
import org.no42.rrdlift.repo.WalkResult;
import org.no42.rrdlift.repo.WorkItem;
import org.no42.rrdlift.rrd.DataSource;
import org.no42.rrdlift.rrd.RrdFile;
import org.no42.rrdlift.rrd.RrdOpener;
import org.no42.rrdlift.samples.Pass;
import org.no42.rrdlift.samples.SampleGenerator;
import org.no42.rrdlift.samples.Series;

/** Decides, for every data source in the repository, which labels (if any) its history is written with. */
public final class Planner {

    private Planner() {}

    /** A data source without a live or exported series, classified once the cutover is known. */
    private record Orphan(String file, WorkItem item, DataSource ds, String mtype, long samples, long firstSampleSec,
                          long lastUpdate, long step) {}

    public static Plan plan(Path rrdDir, RrdOpener opener, LabelIndex live, LabelIndex opennms, boolean skipOrphans)
            throws IOException {
        return plan(rrdDir, opener, live, opennms, new PlanOptions(skipOrphans, false, false, null, null));
    }

    public static Plan plan(Path rrdDir, RrdOpener opener, LabelIndex live, LabelIndex opennms, PlanOptions options)
            throws IOException {
        LabelResolver resolver = new LabelResolver(options.config(), live, options.rest());
        WalkResult walk = RepositoryWalker.walk(rrdDir);
        List<PlanEntry> entries = new ArrayList<>();
        List<Orphan> orphans = new ArrayList<>();
        long oldest = Long.MAX_VALUE;
        long cutover = 0;
        for (WalkResult.Skipped s : walk.skipped()) {
            entries.add(new PlanEntry(s.file().toString(), null, null, null, EntryClass.SKIPPED, null, 0, s.reason()));
        }
        for (WorkItem item : walk.items()) {
            String file = item.file().toString();
            RrdFile rrd;
            Map<String, Series> series = new HashMap<>();
            try {
                rrd = opener.open(item.file());
                for (Series s : SampleGenerator.generate(rrd, Pass.ALL)) {
                    series.put(s.dsName(), s);
                }
            } catch (IOException | RuntimeException e) {
                String note = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                entries.add(new PlanEntry(file, item.resourceId(), null, null, EntryClass.FAILED_READ, null, 0, note));
                continue;
            }
            cutover = Math.max(cutover, rrd.lastUpdate());
            for (DataSource ds : rrd.dataSources()) {
                if (!SampleGenerator.supported(ds)) {
                    entries.add(new PlanEntry(file, item.resourceId(), ds.name(), null, EntryClass.SKIPPED, null, 0,
                            "unsupported data source type " + ds.type()));
                    continue;
                }
                String mtype = ds.isCounter() ? "count" : "gauge";
                Series s = series.get(ds.name());
                long samples = s == null ? 0 : s.size();
                long first = s != null && s.size() > 0 ? s.timesMs()[0] / 1000 : Long.MAX_VALUE;
                SeriesKey key = SeriesKey.forDataSource(item.resourceId(), ds.name());
                List<Map<String, String>> matches = live.lookup(key);
                List<Map<String, String>> exported = matches.isEmpty() && opennms != null ? opennms.lookup(key) : List.of();
                if (matches.isEmpty() && exported.isEmpty()) {
                    orphans.add(new Orphan(file, item, ds, mtype, samples, first, rrd.lastUpdate(), rrd.step()));
                    continue;
                }
                EntryClass cls;
                Map<String, String> labels = null;
                Map<String, String> sources = null;
                List<Map<String, String>> candidates = null;
                String note = null;
                if (matches.size() == 1) {
                    cls = EntryClass.MATCHED;
                    labels = matches.get(0);
                    sources = allFrom(labels, "live");
                } else if (matches.size() > 1) {
                    cls = EntryClass.AMBIGUOUS;
                    note = matches.size() + " live label sets";
                    candidates = matches;
                } else if (exported.size() == 1) {
                    cls = EntryClass.OPENNMS;
                    labels = exported.get(0);
                    sources = allFrom(labels, "opennms-export");
                } else {
                    cls = EntryClass.AMBIGUOUS;
                    note = exported.size() + " exported label sets";
                    candidates = exported;
                }
                if (labels != null && labels.containsKey("mtype") && !mtype.equals(labels.get("mtype"))) {
                    note = "label mtype=" + labels.get("mtype") + ", RRD data source is " + ds.type();
                    cls = EntryClass.MTYPE_MISMATCH;
                    labels = null;
                    sources = null;
                }
                if (labels != null) {
                    oldest = Math.min(oldest, first);
                }
                entries.add(new PlanEntry(file, item.resourceId(), ds.name(), mtype, cls, labels, samples, note,
                        sources, candidates));
            }
        }
        // the snapshot's cutover is what the backend was snapshotted against; the local one is the fallback
        long effectiveCutover = options.snapshotCutoverSec() > 0 ? options.snapshotCutoverSec() : cutover;
        for (Orphan o : orphans) {
            PlanEntry e = classifyOrphan(o, resolver, options, effectiveCutover);
            if (e.labels() != null) {
                oldest = Math.min(oldest, o.firstSampleSec());
            }
            entries.add(e);
        }
        return new Plan(rrdDir.toString(), System.currentTimeMillis() / 1000,
                oldest == Long.MAX_VALUE ? 0 : oldest, List.copyOf(entries));
    }

    private static PlanEntry classifyOrphan(Orphan o, LabelResolver resolver, PlanOptions options, long cutover)
            throws IOException {
        String rid = o.item().resourceId();
        LabelResolver.Result r = resolver.resolve(rid, o.ds().name(), o.mtype());
        if (!r.nodeGone() && options.pending()) {
            String reason = null;
            if (o.lastUpdate() >= cutover - 2 * o.step()) {
                reason = "still written at cutover";
            } else if (resolver.scheduledForCollection(rid)) {
                reason = "scheduled for collection in OpenNMS";
            }
            if (reason != null) {
                return new PlanEntry(o.file(), rid, o.ds().name(), o.mtype(), EntryClass.PENDING, null, o.samples(),
                        reason + "; rerun snapshot-labels and plan after " + Instant.ofEpochSecond(cutover + 2 * o.step()),
                        null, null);
            }
        }
        EntryClass cls;
        String note = null;
        Map<String, String> labels = r.labels();
        if (r.nodeGone()) {
            cls = EntryClass.ORPHAN_MINIMAL;
            note = "node not found in OpenNMS";
        } else if (r.unresolved().isEmpty()) {
            cls = EntryClass.ORPHAN_COMPLETE;
        } else {
            cls = EntryClass.ORPHAN_PARTIAL;
            note = "unresolved: " + String.join(", ", r.unresolved());
        }
        if (options.skipOrphans()) {
            labels = null;
            note = "skipped by --skip-orphans";
        } else if (options.skipPartial() && cls == EntryClass.ORPHAN_PARTIAL) {
            labels = null;
            note = note + " (skipped by --skip-partial)";
        }
        return new PlanEntry(o.file(), rid, o.ds().name(), o.mtype(), cls, labels, o.samples(), note,
                labels == null ? null : r.sources(), null);
    }

    public static Map<String, String> minimalLabels(String resourceId, String dsName, String mtype) {
        Map<String, String> labels = new TreeMap<>();
        labels.put("__name__", Sanitizers.sanitizeMetricName(dsName));
        labels.put("resourceId", Sanitizers.sanitizeLabelValue(resourceId));
        labels.put("mtype", mtype);
        return labels;
    }

    private static Map<String, String> allFrom(Map<String, String> labels, String source) {
        Map<String, String> out = new TreeMap<>();
        labels.keySet().forEach(k -> out.put(k, source));
        return out;
    }
}
