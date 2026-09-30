/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.no42.rrdlift.Main;
import org.no42.rrdlift.labels.LabelIndex;
import org.no42.rrdlift.labels.SeriesKey;
import org.no42.rrdlift.labels.SnapshotInfo;
import org.no42.rrdlift.opennms.MetaTagConfig;
import org.no42.rrdlift.prom.FakeBackend;
import org.no42.rrdlift.prom.PromClient;
import org.no42.rrdlift.prom.TimeSeries;
import org.no42.rrdlift.repo.TestRepos;
import org.no42.rrdlift.rrd.Fixtures;
import org.no42.rrdlift.rrd.NativeRrdtoolReader;

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
    void ignoresSeriesWithoutSamplesAfterTheCutover(@TempDir Path tmp) throws Exception {
        Path repo = TestRepos.create(tmp.resolve("rrd"));
        Path out = tmp.resolve("labels.json");
        long cutover = SnapshotInfo.cutover(repo, new org.no42.rrdlift.rrd.RrdOpener(
                org.no42.rrdlift.rrd.RrdOpener.Mode.NATIVE, null));
        Map<String, String> rrdliftOwn = Map.of("__name__", "icmp", "resourceId", "response/10.0.0.1/icmp", "mtype", "gauge");
        Map<String, String> live = Map.of("__name__", "ifInOctets", "resourceId", "snmp/1/eth0-0011/mib2-interfaces",
                "mtype", "count", "node", "n1");
        try (FakeBackend backend = FakeBackend.start()) {
            PromClient client = new PromClient(backend.writeUrl(), backend.readUrl(), null);
            client.write(List.of(
                    new TimeSeries(rrdliftOwn, new long[] {(cutover - 300) * 1000, cutover * 1000}, new double[] {1, 2}),
                    new TimeSeries(live, new long[] {(cutover - 10) * 1000, (cutover + 60) * 1000}, new double[] {1, 2})));

            // a window that reaches back past the cutover, as with a large --since-days
            int exit = Main.run("snapshot-labels", "--rrd-dir", repo.toString(), "--since-days", "36500",
                    "--read-url", backend.readUrl().toString(), "--out", out.toString());

            assertThat(exit).isZero();
        }
        LabelIndex index = LabelIndex.load(out);
        assertThat(index.keys()).isEqualTo(1);
        assertThat(index.lookup(new SeriesKey("snmp/1/eth0-0011/mib2-interfaces", "ifInOctets"))).containsExactly(live);
        assertThat(index.lookup(new SeriesKey("response/10.0.0.1/icmp", "icmp"))).isEmpty();
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

    @Test
    void writesSidecarWithCutoverAndConfigHash(@TempDir Path tmp) throws Exception {
        Path repo = TestRepos.create(tmp.resolve("rrd"));
        Path home = tmp.resolve("opennms");
        Files.createDirectories(home.resolve("etc"));
        Files.writeString(home.resolve("etc/opennms.properties"),
                "org.opennms.timeseries.tin.metatags.tag.node=${node:label}\n");
        Path out = tmp.resolve("labels-prometheus.json");
        try (FakeBackend backend = FakeBackend.start()) {
            int exit = Main.run("snapshot-labels", "--rrd-dir", repo.toString(), "--read-url", backend.readUrl().toString(),
                    "--out", out.toString(), "--opennms-home", home.toString());
            assertThat(exit).isZero();
        }
        SnapshotInfo info = SnapshotInfo.load(tmp.resolve("labels-prometheus.meta.json"));
        long newest = Math.max(NativeRrdtoolReader.read(Fixtures.path("aarch64", "grp.rrd")).lastUpdate(),
                NativeRrdtoolReader.read(Fixtures.path("aarch64", "icmp.rrd")).lastUpdate());
        assertThat(info.cutoverSec()).isEqualTo(newest);
        assertThat(info.snapshotSec()).isCloseTo(System.currentTimeMillis() / 1000, org.assertj.core.data.Offset.offset(60L));
        assertThat(info.configHash()).isEqualTo(MetaTagConfig.load(home).hash());
        assertThat(info.expectedKeys()).containsExactly("node");
        assertThat(info.backendUrl()).startsWith("http://127.0.0.1:");
        assertThat(SnapshotInfo.sidecarOf(Path.of("x/labels"))).isEqualTo(Path.of("x/labels.meta.json"));
    }

    @Test
    void missingOpennmsHomeFailsWithoutWritingAnyFile(@TempDir Path tmp) throws Exception {
        Path repo = TestRepos.create(tmp.resolve("rrd"));
        Path out = tmp.resolve("labels-prometheus.json");
        try (FakeBackend backend = FakeBackend.start()) {
            int exit = Main.run("snapshot-labels", "--rrd-dir", repo.toString(), "--read-url", backend.readUrl().toString(),
                    "--out", out.toString(), "--opennms-home", tmp.resolve("nope").toString());
            assertThat(exit).isEqualTo(1);
        }
        assertThat(out).doesNotExist();
        assertThat(SnapshotInfo.sidecarOf(out)).doesNotExist();
    }
}
