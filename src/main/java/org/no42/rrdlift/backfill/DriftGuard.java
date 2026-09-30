/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.backfill;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.no42.rrdlift.labels.Sanitizers;
import org.no42.rrdlift.labels.SnapshotInfo;
import org.no42.rrdlift.opennms.MetaTagConfig;
import org.no42.rrdlift.plan.EntryClass;
import org.no42.rrdlift.plan.Plan;
import org.no42.rrdlift.plan.PlanEntry;
import org.no42.rrdlift.prom.PromClient;
import org.no42.rrdlift.prom.Selectors;

/** Stops a backfill before it writes under labels that no longer match the live series. */
public final class DriftGuard {

    public static final class DriftException extends IOException {
        public DriftException(String message) {
            super(message);
        }
    }

    private final Plan plan;
    private final PromClient client;
    private final int canary;
    private final long seed;

    public DriftGuard(Plan plan, PromClient client, int canary, long seed) {
        this.plan = plan;
        this.client = client;
        this.canary = canary;
        this.seed = seed;
    }

    public static void checkConfig(SnapshotInfo snapshot, MetaTagConfig current, boolean skip) throws DriftException {
        if (snapshot == null || snapshot.configHash() == null || skip) {
            return;
        }
        if (current == null) {
            throw new DriftException("the snapshot recorded a meta-tag config hash; pass --opennms-home to check it"
                    + " or --skip-config-check");
        }
        if (!snapshot.configHash().equals(current.hash())) {
            throw new DriftException("meta-tag config changed since the snapshot; rerun snapshot-labels and plan");
        }
    }

    public void checkLive(long nowSec) throws IOException {
        if (canary <= 0) {
            return;
        }
        long start = plan.snapshot() != null ? plan.snapshot().snapshotSec() - 86400 : nowSec - 7 * 86400L;
        List<String> differences = new ArrayList<>();
        for (PlanEntry e : sample(EntryClass.MATCHED)) {
            for (Map<String, String> live : series(e, start, nowSec)) {
                if (!live.equals(e.labels())) {
                    differences.add(e.resourceId() + " " + e.dsName() + ": planned " + new TreeMap<>(e.labels())
                            + ", live " + new TreeMap<>(live));
                    break;
                }
            }
        }
        if (!differences.isEmpty()) {
            throw new DriftException("live labels changed since the snapshot for " + differences.size()
                    + " sampled series; rerun snapshot-labels and plan\n  "
                    + String.join("\n  ", differences.subList(0, Math.min(20, differences.size()))));
        }
        int late = 0;
        for (PlanEntry e : plan.entries()) {
            if (e.entryClass() == EntryClass.PENDING && !series(e, start, nowSec).isEmpty()) {
                late++;
            }
        }
        for (PlanEntry e : sampleOrphans()) {
            for (Map<String, String> live : liveSeries(e, start, nowSec)) {
                if (!live.equals(e.labels())) {
                    late++;
                    break;
                }
            }
        }
        if (late > 0) {
            throw new DriftException("live series appeared for " + late + " planned orphans; rerun snapshot-labels and plan");
        }
    }

    private List<PlanEntry> sample(EntryClass cls) {
        List<PlanEntry> candidates = new ArrayList<>();
        for (PlanEntry e : plan.entries()) {
            if (e.entryClass() == cls && e.labels() != null) {
                candidates.add(e);
            }
        }
        Collections.shuffle(candidates, new Random(seed));
        return candidates.subList(0, Math.min(canary, candidates.size()));
    }

    private List<PlanEntry> sampleOrphans() {
        List<PlanEntry> candidates = new ArrayList<>();
        for (PlanEntry e : plan.entries()) {
            if (e.entryClass().isOrphan() && e.labels() != null) {
                candidates.add(e);
            }
        }
        Collections.shuffle(candidates, new Random(seed));
        return candidates.subList(0, Math.min(canary, candidates.size()));
    }

    /**
     * Series OpenNMS still writes. rrdlift never writes after the cutover, so with a snapshot only series
     * with a sample newer than the cutover count; its own earlier writes under older labels do not.
     */
    private List<Map<String, String>> liveSeries(PlanEntry e, long start, long nowSec) throws IOException {
        if (plan.snapshot() == null) {
            return series(e, start, nowSec);
        }
        long cutover = plan.snapshot().cutoverSec();
        String selector = Selectors.exact(Map.of("resourceId", Sanitizers.sanitizeLabelValue(e.resourceId()),
                "__name__", Sanitizers.sanitizeMetricName(e.dsName())));
        List<Map<String, String>> live = new ArrayList<>();
        for (PromClient.QueryResult r : client.query(selector + "[" + Math.max(1, nowSec - cutover) + "s]", nowSec)) {
            for (long t : r.timesMs()) {
                if (t > cutover * 1000) {
                    live.add(r.metric());
                    break;
                }
            }
        }
        return live;
    }

    private List<Map<String, String>> series(PlanEntry e, long start, long end) throws IOException {
        return client.series(Selectors.exact(Map.of("resourceId", Sanitizers.sanitizeLabelValue(e.resourceId()),
                "__name__", Sanitizers.sanitizeMetricName(e.dsName()))), start, end + 1);
    }
}
