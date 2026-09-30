/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.no42.rrdlift.labels.LabelIndex;
import org.no42.rrdlift.labels.SnapshotInfo;
import org.no42.rrdlift.opennms.MetaTagConfig;
import org.no42.rrdlift.plan.EntryClass;
import org.no42.rrdlift.plan.Plan;
import org.no42.rrdlift.plan.PlanEntry;
import org.no42.rrdlift.plan.Planner;
import org.no42.rrdlift.prom.FakeBackend;
import org.no42.rrdlift.prom.PromClient;
import org.no42.rrdlift.prom.TimeSeries;
import org.no42.rrdlift.repo.TestRepos;
import org.no42.rrdlift.rrd.RrdOpener;

class DriftGuardTest {

    static final long NOW = System.currentTimeMillis() / 1000;
    static final Map<String, String> PLANNED = Map.of("__name__", "ifInOctets", "resourceId", "snmp/1/eth0/mib2-interfaces",
            "mtype", "count", "node", "n1");

    FakeBackend backend;
    PromClient client;

    @BeforeEach
    void start() throws Exception {
        backend = FakeBackend.start();
        client = new PromClient(backend.writeUrl(), backend.readUrl(), null);
    }

    @AfterEach
    void stop() {
        backend.close();
    }

    static Plan plan(PlanEntry... entries) {
        return new Plan("rrd", NOW, 0, List.of(entries), new SnapshotInfo(NOW - 60, NOW - 120, null, null, null));
    }

    static PlanEntry matched() {
        return new PlanEntry("a.rrd", "snmp/1/eth0/mib2-interfaces", "ifInOctets", "count", EntryClass.MATCHED, PLANNED, 1, null);
    }

    void live(Map<String, String> labels) throws Exception {
        client.write(List.of(new TimeSeries(labels, new long[] {NOW * 1000}, new double[] {1})));
    }

    @Test
    void configCheck() {
        MetaTagConfig a = MetaTagConfig.fromProperties(Map.of("org.opennms.timeseries.tin.metatags.tag.node", "${node:label}"));
        MetaTagConfig b = MetaTagConfig.fromProperties(Map.of("org.opennms.timeseries.tin.metatags.tag.node", "${node:foreign-id}"));
        SnapshotInfo withHash = new SnapshotInfo(1, 1, null, a.hash(), List.of("node"));
        assertThatCode(() -> DriftGuard.checkConfig(new SnapshotInfo(1, 1, null, null, null), null, false)).doesNotThrowAnyException();
        assertThatCode(() -> DriftGuard.checkConfig(withHash, a, false)).doesNotThrowAnyException();
        assertThatCode(() -> DriftGuard.checkConfig(withHash, null, true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> DriftGuard.checkConfig(withHash, null, false)).isInstanceOf(DriftGuard.DriftException.class)
                .hasMessageContaining("--opennms-home");
        assertThatThrownBy(() -> DriftGuard.checkConfig(withHash, b, false)).isInstanceOf(DriftGuard.DriftException.class)
                .hasMessageContaining("meta-tag config changed since the snapshot");
    }

    @Test
    void unchangedLiveLabelsPass() throws Exception {
        live(PLANNED);
        assertThatCode(() -> new DriftGuard(plan(matched()), client, 50, 1).checkLive(NOW)).doesNotThrowAnyException();
    }

    @Test
    void renamedNodeIsDetectedEvenAfterThePlannedSetWasWritten() throws Exception {
        live(PLANNED); // what the RECENT pass wrote
        Map<String, String> renamed = new java.util.HashMap<>(PLANNED);
        renamed.put("node", "n1-new");
        live(renamed); // OpenNMS now writes the new label set
        assertThatThrownBy(() -> new DriftGuard(plan(matched()), client, 50, 1).checkLive(NOW))
                .isInstanceOf(DriftGuard.DriftException.class)
                .hasMessageContaining("live labels changed since the snapshot for 1 sampled series");
    }

    static PlanEntry pending(int n) {
        return new PlanEntry("b" + n + ".rrd", "snmp/1/eth" + n + "/mib2-interfaces", "ifInOctets", "count",
                EntryClass.PENDING, null, 1, "still written at cutover");
    }

    static String out(ByteArrayOutputStream buf) {
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void liveSeriesForPendingIsANoticeNotAStop() throws Exception {
        live(Map.of("__name__", "ifInOctets", "resourceId", "snmp/1/eth1/mib2-interfaces", "mtype", "count", "node", "n1"));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        assertThatCode(() -> new DriftGuard(plan(pending(1)), client, 50, 1, new PrintStream(buf, true))
                .checkLive(NOW)).doesNotThrowAnyException();
        assertThat(out(buf)).isEqualTo("1 pending resources now have a live series;"
                + " rerun snapshot-labels and plan after this backfill" + System.lineSeparator());
    }

    @Test
    void noNoticeWhenNoPendingResourceHasALiveSeries() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        new DriftGuard(plan(pending(1)), client, 50, 1, new PrintStream(buf, true)).checkLive(NOW);
        assertThat(out(buf)).isEmpty();
    }

    @Test
    void pendingQueriesAreBoundedByTheCanarySize() throws Exception {
        new DriftGuard(plan(pending(1), pending(2), pending(3), pending(4)), client, 2, 1,
                new PrintStream(PrintStream.nullOutputStream())).checkLive(NOW);
        assertThat(backend.requests()).filteredOn(r -> r.startsWith("GET series")).hasSize(2);
    }

    @Test
    void pendingNoticeDoesNotHideAnOrphanStop() throws Exception {
        live(Map.of("__name__", "ifInOctets", "resourceId", "snmp/1/eth1/mib2-interfaces", "mtype", "count", "node", "n1"));
        client.write(List.of(new TimeSeries(ORPHAN_OLD, new long[] {(NOW - 120) * 1000, (NOW - 30) * 1000},
                new double[] {1, 2})));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        assertThatThrownBy(() -> new DriftGuard(plan(pending(1), orphan()), client, 50, 1, new PrintStream(buf, true))
                .checkLive(NOW))
                .isInstanceOf(DriftGuard.DriftException.class)
                .hasMessageContaining("live series appeared for 1 planned orphans");
    }

    static final Map<String, String> ORPHAN_OLD = Map.of("__name__", "ifInOctets", "resourceId", "snmp/1/eth2/mib2-interfaces",
            "mtype", "count");
    static final Map<String, String> ORPHAN_NEW = Map.of("__name__", "ifInOctets", "resourceId", "snmp/1/eth2/mib2-interfaces",
            "mtype", "count", "node", "n1");

    static PlanEntry orphan() {
        return new PlanEntry("c.rrd", "snmp/1/eth2/mib2-interfaces", "ifInOctets", "count", EntryClass.ORPHAN_COMPLETE,
                ORPHAN_NEW, 1, null);
    }

    @Test
    void ownEarlierOrphanWritesBeforeCutoverAreNotLive() throws Exception {
        client.write(List.of(new TimeSeries(ORPHAN_OLD, new long[] {(NOW - 120) * 1000}, new double[] {1})));
        assertThatCode(() -> new DriftGuard(plan(orphan()), client, 50, 1).checkLive(NOW)).doesNotThrowAnyException();
    }

    @Test
    void orphanSeriesWithSamplesAfterCutoverIsLive() throws Exception {
        client.write(List.of(new TimeSeries(ORPHAN_OLD, new long[] {(NOW - 120) * 1000, (NOW - 30) * 1000},
                new double[] {1, 2})));
        assertThatThrownBy(() -> new DriftGuard(plan(orphan()), client, 50, 1).checkLive(NOW))
                .isInstanceOf(DriftGuard.DriftException.class)
                .hasMessageContaining("live series appeared for 1 planned orphans");
    }

    @Test
    void snapshotCutoverZeroIsUnknownAndUsesTheSeriesEndpoint() throws Exception {
        client.write(List.of(new TimeSeries(ORPHAN_OLD, new long[] {(NOW - 30) * 1000}, new double[] {1})));
        Plan p = new Plan("rrd", NOW, 0, List.of(orphan()), new SnapshotInfo(NOW - 60, 0, null, null, null));
        assertThatThrownBy(() -> new DriftGuard(p, client, 50, 1).checkLive(NOW))
                .isInstanceOf(DriftGuard.DriftException.class);
        assertThat(backend.requests()).noneMatch(r -> r.startsWith("GET query"));
    }

    @Test
    void canaryZeroDoesNothing() throws Exception {
        new DriftGuard(plan(matched()), client, 0, 1).checkLive(NOW);
        assertThat(backend.requests()).noneMatch(r -> r.startsWith("GET series"));
    }

    @Test
    void guardFailureStopsBackfillBeforeWriting(@TempDir Path tmp) throws Exception {
        RrdOpener opener = new RrdOpener(RrdOpener.Mode.NATIVE, null);
        Plan p = Planner.plan(TestRepos.create(tmp.resolve("rrd")), opener, new LabelIndex(), null, false);
        Backfiller b = new Backfiller(client, opener, new Checkpoint(tmp), new RateLimiter(0), 1, 2000, 0, n -> { },
                new PrintStream(PrintStream.nullOutputStream()));
        b.setPassGuard(pass -> {
            throw new DriftGuard.DriftException("drift");
        });
        assertThatThrownBy(() -> b.run(p, false)).isInstanceOf(DriftGuard.DriftException.class);
        assertThat(backend.writeCount()).isZero();
    }
}
