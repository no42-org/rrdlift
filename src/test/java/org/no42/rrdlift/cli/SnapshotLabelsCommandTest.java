/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.no42.rrdlift.Main;
import org.no42.rrdlift.labels.LabelIndex;
import org.no42.rrdlift.labels.SeriesKey;
import org.no42.rrdlift.prom.FakeBackend;
import org.no42.rrdlift.prom.PromClient;
import org.no42.rrdlift.prom.TimeSeries;
import org.no42.rrdlift.repo.TestRepos;

class SnapshotLabelsCommandTest {

    @Test
    void snapshotsLiveSeriesPerNodeDirectory(@TempDir Path tmp) throws Exception {
        Path repo = TestRepos.create(tmp.resolve("rrd"));
        Path out = tmp.resolve("labels.json");
        try (FakeBackend backend = FakeBackend.start()) {
            PromClient client = new PromClient(backend.writeUrl(), backend.readUrl(), null);
            long nowMs = System.currentTimeMillis();
            Map<String, String> icmp = Map.of("__name__", "icmp", "resourceId", "response/10.0.0.1/icmp",
                    "mtype", "gauge", "node", "n1");
            Map<String, String> foreign = Map.of("__name__", "icmp", "resourceId", "response/10.0.0.10/icmp",
                    "mtype", "gauge");
            client.write(List.of(new TimeSeries(icmp, new long[] {nowMs}, new double[] {1}),
                    new TimeSeries(foreign, new long[] {nowMs}, new double[] {1})));

            int exit = Main.run("snapshot-labels", "--rrd-dir", repo.toString(),
                    "--read-url", backend.readUrl().toString(), "--out", out.toString());

            assertThat(exit).isZero();
            assertThat(backend.requests()).filteredOn(r -> r.startsWith("GET series")).hasSize(3);
            LabelIndex index = LabelIndex.load(out);
            assertThat(index.keys()).isEqualTo(1);
            assertThat(index.lookup(new SeriesKey("response/10.0.0.1/icmp", "icmp"))).containsExactly(icmp);
        }
    }

    @Test
    void failsWithExitCodeOneWhenBackendIsUnreachable(@TempDir Path tmp) throws Exception {
        Path repo = TestRepos.create(tmp.resolve("rrd"));
        int exit = Main.run("snapshot-labels", "--rrd-dir", repo.toString(),
                "--read-url", "http://127.0.0.1:1", "--out", tmp.resolve("x.json").toString());
        assertThat(exit).isEqualTo(1);
    }

    @Test
    void missingReadUrlExitsWith64(@TempDir Path tmp) throws Exception {
        Path repo = TestRepos.create(tmp.resolve("rrd"));
        assertThat(Main.run("snapshot-labels", "--rrd-dir", repo.toString(),
                "--out", tmp.resolve("x.json").toString())).isEqualTo(64);
    }
}
