/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.no42.rrdlift.Main;
import org.no42.rrdlift.plan.EntryClass;
import org.no42.rrdlift.plan.Plan;
import org.no42.rrdlift.plan.PlanEntry;
import org.no42.rrdlift.prom.PromClient;
import org.no42.rrdlift.prom.Selectors;
import org.no42.rrdlift.prom.TimeSeries;
import org.no42.rrdlift.repo.TestRepos;
import org.no42.rrdlift.rrd.Fixtures;
import org.no42.rrdlift.rrd.NativeRrdtoolReader;

/** The operator workflow from the spec, end to end, against a real backend. */
final class Migration {

    static final Map<String, String> LIVE_ICMP = Map.of("__name__", "icmp", "resourceId", "response/10.0.0.1/icmp",
            "mtype", "gauge", "node", "n1");

    private Migration() {}

    static void run(Path tmp, URI writeUrl, URI readUrl, Runnable flush, double tolerance) throws Exception {
        Path repo = TestRepos.create(tmp.resolve("rrd"));
        String w = writeUrl.toString();
        String r = readUrl.toString();
        PromClient client = new PromClient(writeUrl, readUrl, null);

        // What OpenNMS writes live after the cutover, with a meta tag the RRD files know nothing about.
        long now = System.currentTimeMillis();
        client.write(List.of(new TimeSeries(LIVE_ICMP, new long[] {now}, new double[] {7.5})));
        flush.run();

        assertThat(Main.run("preflight", "--rrd-dir", repo.toString(), "--write-url", w, "--read-url", r)).isZero();

        Path labels = tmp.resolve("labels.json");
        assertThat(Main.run("snapshot-labels", "--rrd-dir", repo.toString(), "--read-url", r,
                "--out", labels.toString())).isZero();

        Path planFile = tmp.resolve("plan.json");
        assertThat(Main.run("plan", "--no-pending", "--rrd-dir", repo.toString(), "--labels", labels.toString(),
                "--out", planFile.toString(), "--orphans", tmp.resolve("orphans.txt").toString(),
                "--not-migrated", tmp.resolve("not-migrated.txt").toString())).isZero();
        Plan plan = Plan.load(planFile);
        PlanEntry icmp = plan.entries().stream().filter(e -> "icmp".equals(e.dsName())).findFirst().orElseThrow();
        assertThat(icmp.entryClass()).isEqualTo(EntryClass.MATCHED);
        assertThat(icmp.labels()).containsEntry("node", "n1");

        assertThat(Main.run("backfill", "--plan", planFile.toString(), "--write-url", w,
                "--state-dir", tmp.toString(), "--rate", "0")).isZero();
        flush.run();

        assertThat(Main.run("verify", "--plan", planFile.toString(), "--read-url", r,
                "--state-dir", tmp.toString(), "--all", "--tolerance", String.valueOf(tolerance))).isZero();

        // History joined the live series: the series with the meta tag now reaches back months.
        long lastUpdate = NativeRrdtoolReader.read(Fixtures.path("aarch64", "icmp.rrd")).lastUpdate();
        long old = lastUpdate - 300L * 86400;
        List<PromClient.QueryResult> past = client.query(Selectors.exact(LIVE_ICMP) + "[2d]", old);
        assertThat(past).singleElement().satisfies(q -> assertThat(q.values()).isNotEmpty());
    }
}
