/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.plan;

import java.io.IOException;
import java.nio.file.Path;
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

    public static Plan plan(Path rrdDir, RrdOpener opener, LabelIndex live, LabelIndex opennms, boolean skipOrphans)
            throws IOException {
        return plan(rrdDir, opener, live, opennms, new PlanOptions(skipOrphans, false, null, null));
    }

    public static Plan plan(Path rrdDir, RrdOpener opener, LabelIndex live, LabelIndex opennms, PlanOptions options)
            throws IOException {
        LabelResolver resolver = new LabelResolver(options.config(), live, options.rest());
        WalkResult walk = RepositoryWalker.walk(rrdDir);
        List<PlanEntry> entries = new ArrayList<>();
        long oldest = Long.MAX_VALUE;
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
            for (DataSource ds : rrd.dataSources()) {
                if (!SampleGenerator.supported(ds)) {
                    entries.add(new PlanEntry(file, item.resourceId(), ds.name(), null, EntryClass.SKIPPED, null, 0,
                            "unsupported data source type " + ds.type()));
                    continue;
                }
                String mtype = ds.isCounter() ? "count" : "gauge";
                SeriesKey key = SeriesKey.forDataSource(item.resourceId(), ds.name());
                List<Map<String, String>> matches = live.lookup(key);
                EntryClass cls;
                Map<String, String> labels = null;
                String note = null;
                Map<String, String> sources = null;
                List<Map<String, String>> candidates = null;
                if (matches.size() == 1) {
                    cls = EntryClass.MATCHED;
                    labels = matches.get(0);
                    sources = allFrom(labels, "live");
                } else if (matches.size() > 1) {
                    cls = EntryClass.AMBIGUOUS;
                    note = matches.size() + " live label sets";
                    candidates = matches;
                } else {
                    List<Map<String, String>> exported = opennms == null ? List.of() : opennms.lookup(key);
                    if (exported.size() == 1) {
                        cls = EntryClass.OPENNMS;
                        labels = exported.get(0);
                        sources = allFrom(labels, "opennms-export");
                    } else if (exported.size() > 1) {
                        cls = EntryClass.AMBIGUOUS;
                        note = exported.size() + " exported label sets";
                        candidates = exported;
                    } else {
                        LabelResolver.Result r = resolver.resolve(item.resourceId(), ds.name(), mtype);
                        labels = r.labels();
                        sources = r.sources();
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
                    }
                }
                if ((cls == EntryClass.MATCHED || cls == EntryClass.OPENNMS) && labels.containsKey("mtype")
                        && !mtype.equals(labels.get("mtype"))) {
                    note = "label mtype=" + labels.get("mtype") + ", RRD data source is " + ds.type();
                    cls = EntryClass.MTYPE_MISMATCH;
                    labels = null;
                    sources = null;
                }
                Series s = series.get(ds.name());
                if (labels != null && s != null && s.size() > 0) {
                    oldest = Math.min(oldest, s.timesMs()[0] / 1000);
                }
                entries.add(new PlanEntry(file, item.resourceId(), ds.name(), mtype, cls, labels,
                        s == null ? 0 : s.size(), note, labels == null ? null : sources, candidates));
            }
        }
        return new Plan(rrdDir.toString(), System.currentTimeMillis() / 1000,
                oldest == Long.MAX_VALUE ? 0 : oldest, List.copyOf(entries));
    }

    private static Map<String, String> allFrom(Map<String, String> labels, String source) {
        Map<String, String> out = new TreeMap<>();
        labels.keySet().forEach(k -> out.put(k, source));
        return out;
    }

    public static Map<String, String> minimalLabels(String resourceId, String dsName, String mtype) {
        Map<String, String> labels = new TreeMap<>();
        labels.put("__name__", Sanitizers.sanitizeMetricName(dsName));
        labels.put("resourceId", Sanitizers.sanitizeLabelValue(resourceId));
        labels.put("mtype", mtype);
        return labels;
    }
}
