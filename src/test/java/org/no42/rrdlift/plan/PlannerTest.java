/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.no42.rrdlift.labels.LabelIndex;
import org.no42.rrdlift.repo.TestRepos;
import org.no42.rrdlift.rrd.RrdOpener;

class PlannerTest {

    static final String ETH0 = "snmp/1/eth0-0011/mib2-interfaces";
    static final RrdOpener OPENER = new RrdOpener(RrdOpener.Mode.NATIVE, null);

    static LabelIndex live() {
        LabelIndex live = new LabelIndex();
        live.add(Map.of("__name__", "icmp", "resourceId", "response/10.0.0.1/icmp", "mtype", "gauge", "node", "n1"));
        live.add(Map.of("__name__", "ifInOctets", "resourceId", ETH0, "mtype", "count", "node", "a"));
        live.add(Map.of("__name__", "ifInOctets", "resourceId", ETH0, "mtype", "count", "node", "b"));
        return live;
    }

    static LabelIndex opennms() {
        LabelIndex o = new LabelIndex();
        o.add(Map.of("__name__", "ifOutOctets", "resourceId", ETH0, "mtype", "count", "node", "from-export"));
        return o;
    }

    static Map<String, PlanEntry> byName(Plan plan) {
        return plan.entries().stream().filter(e -> e.dsName() != null)
                .collect(Collectors.toMap(e -> e.resourceId() + "|" + e.dsName(), Function.identity()));
    }

    @Test
    void classifiesEveryDataSource(@TempDir Path tmp) throws Exception {
        Plan plan = Planner.plan(TestRepos.create(tmp), OPENER, live(), opennms(), false);
        Map<String, PlanEntry> e = byName(plan);

        assertThat(e.get("response/10.0.0.1/icmp|icmp").entryClass()).isEqualTo(EntryClass.MATCHED);
        assertThat(e.get("response/10.0.0.1/icmp|icmp").labels()).containsEntry("node", "n1");
        assertThat(e.get(ETH0 + "|ifInOctets").entryClass()).isEqualTo(EntryClass.AMBIGUOUS);
        assertThat(e.get(ETH0 + "|ifInOctets").labels()).isNull();
        assertThat(e.get(ETH0 + "|ifOutOctets").entryClass()).isEqualTo(EntryClass.OPENNMS);
        assertThat(e.get(ETH0 + "|ifOutOctets").labels()).containsEntry("node", "from-export");
        PlanEntry speed = e.get(ETH0 + "|ifSpeed");
        assertThat(speed.entryClass()).isEqualTo(EntryClass.ORPHAN);
        assertThat(speed.labels()).containsExactlyInAnyOrderEntriesOf(
                Map.of("__name__", "ifSpeed", "resourceId", ETH0, "mtype", "gauge"));
        assertThat(e.get(ETH0 + "|ifInErrors").labels()).containsEntry("mtype", "count");
        assertThat(speed.samples()).isGreaterThan(2000);
        assertThat(plan.entries()).hasSize(10); // 8 grp + icmp + node-empty x
        assertThat(plan.oldestSampleSec()).isPositive();
        assertThat(plan.writableByFile()).hasSize(3);
    }

    @Test
    void skipOrphansKeepsThemListedButUnwritable(@TempDir Path tmp) throws Exception {
        Plan plan = Planner.plan(TestRepos.create(tmp), OPENER, live(), null, true);
        PlanEntry speed = byName(plan).get(ETH0 + "|ifSpeed");
        assertThat(speed.entryClass()).isEqualTo(EntryClass.ORPHAN);
        assertThat(speed.labels()).isNull();
        assertThat(byName(plan).get(ETH0 + "|ifOutOctets").entryClass()).isEqualTo(EntryClass.ORPHAN);
    }

    @Test
    void reportsUnreadableAndSkippedFiles(@TempDir Path tmp) throws Exception {
        Path repo = TestRepos.create(tmp);
        Path bad = Files.createDirectories(repo.resolve("snmp/9"));
        Files.writeString(bad.resolve("ds.properties"), "a=bad\n");
        Files.write(bad.resolve("bad.rrd"), new byte[] {'R', 'R', 'D', 0, 1, 2, 3});
        Files.write(bad.resolve("stray.rrd"), new byte[] {0});

        Plan plan = Planner.plan(repo, OPENER, new LabelIndex(), null, false);
        assertThat(plan.entries()).filteredOn(p -> p.entryClass() == EntryClass.FAILED_READ)
                .extracting(PlanEntry::resourceId).containsExactly("snmp/9/bad");
        assertThat(plan.entries()).filteredOn(p -> p.entryClass() == EntryClass.SKIPPED)
                .extracting(PlanEntry::note).containsExactly("no group information");
    }

    @Test
    void roundTripsThroughJson(@TempDir Path tmp) throws Exception {
        Plan plan = Planner.plan(TestRepos.create(tmp.resolve("rrd")), OPENER, live(), null, false);
        Path file = tmp.resolve("plan.json");
        plan.save(file);
        assertThat(Plan.load(file)).isEqualTo(plan);
    }
}
