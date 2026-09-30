/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.no42.rrdlift.labels.LabelIndex;
import org.no42.rrdlift.opennms.FakeOpennms;
import org.no42.rrdlift.opennms.OpennmsClient;
import org.no42.rrdlift.repo.TestRepos;
import org.no42.rrdlift.rrd.Fixtures;
import org.no42.rrdlift.rrd.NativeRrdtoolReader;
import org.no42.rrdlift.rrd.RrdOpener;

class PendingTest {

    static final RrdOpener OPENER = new RrdOpener(RrdOpener.Mode.NATIVE, null);
    static final String ETH0 = "snmp/1/eth0-0011/mib2-interfaces";

    static Map<String, PlanEntry> byName(Plan plan) {
        return plan.entries().stream().filter(e -> e.dsName() != null)
                .collect(Collectors.toMap(e -> e.resourceId() + "|" + e.dsName(), Function.identity()));
    }

    @Test
    void resourcesWrittenAtCutoverArePending(@TempDir Path tmp) throws Exception {
        Plan plan = Planner.plan(TestRepos.create(tmp), OPENER, new LabelIndex(), null,
                new PlanOptions(false, false, true, null, null));
        long cutover = NativeRrdtoolReader.read(Fixtures.path("aarch64", "grp.rrd")).lastUpdate();
        PlanEntry speed = byName(plan).get(ETH0 + "|ifSpeed");
        assertThat(speed.entryClass()).isEqualTo(EntryClass.PENDING);
        assertThat(speed.labels()).isNull();
        assertThat(speed.note()).startsWith("still written at cutover")
                .endsWith(Instant.ofEpochSecond(cutover + 600).toString());
        assertThat(byName(plan).get("response/10.0.0.1/icmp|icmp").entryClass()).isEqualTo(EntryClass.PENDING);
        assertThat(byName(plan).get("snmp/fs/fs1/fid1/node-empty|x").entryClass()).isEqualTo(EntryClass.ORPHAN_PARTIAL);
    }

    @Test
    void snapshotCutoverReplacesThePlanLocalOne(@TempDir Path tmp) throws Exception {
        Path repo = TestRepos.create(tmp);
        long local = NativeRrdtoolReader.read(Fixtures.path("aarch64", "grp.rrd")).lastUpdate();
        // the snapshot saw a cutover a day later, so nothing in this repository was written at it
        Plan later = Planner.plan(repo, OPENER, new LabelIndex(), null,
                new PlanOptions(false, false, true, null, null, local + 86400));
        assertThat(later.entries()).noneMatch(e -> e.entryClass() == EntryClass.PENDING);
        // a snapshot cutover just after the newest file keeps the entry pending and dates the rerun from it
        Plan same = Planner.plan(repo, OPENER, new LabelIndex(), null,
                new PlanOptions(false, false, true, null, null, local + 60));
        assertThat(byName(same).get(ETH0 + "|ifSpeed").note()).endsWith(Instant.ofEpochSecond(local + 60 + 600).toString());
        // 0 means unknown: the plan-local cutover applies
        Plan unknown = Planner.plan(repo, OPENER, new LabelIndex(), null,
                new PlanOptions(false, false, true, null, null, 0));
        assertThat(byName(unknown).get(ETH0 + "|ifSpeed").note()).endsWith(Instant.ofEpochSecond(local + 600).toString());
    }

    @Test
    void ruleCanBeSwitchedOff(@TempDir Path tmp) throws Exception {
        Plan plan = Planner.plan(TestRepos.create(tmp), OPENER, new LabelIndex(), null,
                new PlanOptions(false, false, false, null, null));
        assertThat(plan.entries()).noneMatch(e -> e.entryClass() == EntryClass.PENDING);
    }

    @Test
    void interfaceScheduledInOpennmsIsPending(@TempDir Path tmp) throws Exception {
        Path repo = TestRepos.create(tmp.resolve("rrd"));
        for (int node : new int[] {5, 6}) {
            Path dir = Files.createDirectories(repo.resolve("snmp/" + node + "/eth9-001122334455"));
            Files.writeString(dir.resolve("ds.properties"), "x=mib2-old\n");
            Files.copy(Fixtures.path("aarch64", "empty.rrd"), dir.resolve("mib2-old.rrd"));
        }
        try (FakeOpennms onms = FakeOpennms.start()) {
            onms.addNode(5, null, null, "n5", "dc", List.of(), Map.of());
            onms.addNode(6, null, null, "n6", "dc", List.of(), Map.of());
            onms.addSnmpInterface(5, "eth9", null, "001122334455", true);
            onms.addSnmpInterface(6, "eth9", null, "001122334455", false);
            OpennmsClient rest = new OpennmsClient(onms.url(), "admin", "admin");
            Plan plan = Planner.plan(repo, OPENER, new LabelIndex(), null, new PlanOptions(false, false, true, null, rest));
            PlanEntry five = byName(plan).get("snmp/5/eth9-001122334455/mib2-old|x");
            assertThat(five.entryClass()).isEqualTo(EntryClass.PENDING);
            assertThat(five.note()).startsWith("scheduled for collection in OpenNMS");
            assertThat(byName(plan).get("snmp/6/eth9-001122334455/mib2-old|x").entryClass().isOrphan()).isTrue();
        }
    }
}
