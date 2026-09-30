/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.no42.rrdlift.Main;
import org.no42.rrdlift.plan.EntryClass;
import org.no42.rrdlift.plan.Plan;
import org.no42.rrdlift.plan.PlanEntry;

class ExplainCommandTest {

    static int lastExit;

    static String capture(String... args) {
        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true));
        try {
            lastExit = Main.run(args);
        } finally {
            System.setOut(original);
        }
        return out.toString();
    }

    @Test
    void printsLabelsAndSources(@TempDir Path tmp) throws Exception {
        Path planFile = tmp.resolve("plan.json");
        new Plan("rrd", 1, 0, List.of(
                new PlanEntry("a.rrd", "snmp/1/eth1/mib2-interfaces", "ifInOctets", "count", EntryClass.ORPHAN_COMPLETE,
                        Map.of("__name__", "ifInOctets", "node", "router1"), 10, null,
                        Map.of("__name__", "minimal", "node", "sibling:snmp/1/eth0/mib2-interfaces"), null),
                new PlanEntry("a.rrd", "snmp/1/eth1/mib2-interfaces", "ifOutOctets", "count", EntryClass.AMBIGUOUS, null, 10,
                        "2 live label sets", null, List.of(Map.of("node", "a"), Map.of("node", "b"))))).save(planFile);

        String out = capture("explain", "--plan", planFile.toString(), "snmp/1/eth1/mib2-interfaces");

        assertThat(lastExit).isZero();
        assertThat(out).contains("snmp/1/eth1/mib2-interfaces ifInOctets: ORPHAN_COMPLETE")
                .contains("  node=router1  (sibling:snmp/1/eth0/mib2-interfaces)")
                .contains("snmp/1/eth1/mib2-interfaces ifOutOctets: AMBIGUOUS (not written)")
                .contains("  candidate: {node=a}").contains("  note: 2 live label sets");

        String one = capture("explain", "--plan", planFile.toString(), "--ds", "ifInOctets", "snmp/1/eth1/mib2-interfaces");
        assertThat(one).contains("ifInOctets").doesNotContain("ifOutOctets");
    }

    @Test
    void unknownResourceExitsOne(@TempDir Path tmp) throws Exception {
        Path planFile = tmp.resolve("plan.json");
        new Plan("rrd", 1, 0, List.of()).save(planFile);
        assertThat(Main.run("explain", "--plan", planFile.toString(), "snmp/9/x")).isEqualTo(1);
    }
}
