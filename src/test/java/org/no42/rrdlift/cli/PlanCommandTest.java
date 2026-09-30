/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.no42.rrdlift.Main;
import java.util.List;
import org.no42.rrdlift.labels.LabelIndex;
import org.no42.rrdlift.labels.SnapshotInfo;
import org.no42.rrdlift.plan.Plan;
import org.no42.rrdlift.repo.TestRepos;

class PlanCommandTest {

    @Test
    void writesPlanAndOrphanList(@TempDir Path tmp) throws Exception {
        Path repo = TestRepos.create(tmp.resolve("rrd"));
        Path labels = tmp.resolve("labels.json");
        new LabelIndex().save(labels);
        Path plan = tmp.resolve("plan.json");
        Path orphans = tmp.resolve("orphans.txt");

        int exit = Main.run("plan", "--no-pending", "--rrd-dir", repo.toString(), "--labels", labels.toString(),
                "--out", plan.toString(), "--orphans", orphans.toString(),
                "--not-migrated", tmp.resolve("not-migrated.txt").toString());

        assertThat(exit).isZero();
        assertThat(Plan.load(plan).entries()).hasSize(10);
        assertThat(Files.readAllLines(orphans)).containsExactly(
                "response/10.0.0.1/icmp", "snmp/1/eth0-0011/mib2-interfaces", "snmp/fs/fs1/fid1/node-empty");
    }

    @Test
    void writesNotMigratedListWithAmbiguousEntry(@TempDir Path tmp) throws Exception {
        Path repo = TestRepos.create(tmp.resolve("rrd"));
        LabelIndex live = new LabelIndex();
        String eth0 = "snmp/1/eth0-0011/mib2-interfaces";
        live.add(java.util.Map.of("__name__", "ifInOctets", "resourceId", eth0, "mtype", "count", "node", "a"));
        live.add(java.util.Map.of("__name__", "ifInOctets", "resourceId", eth0, "mtype", "count", "node", "b"));
        Path labels = tmp.resolve("labels.json");
        live.save(labels);
        Path notMigrated = tmp.resolve("not-migrated.txt");

        int exit = Main.run("plan", "--no-pending", "--rrd-dir", repo.toString(), "--labels", labels.toString(),
                "--out", tmp.resolve("plan.json").toString(), "--orphans", tmp.resolve("orphans.txt").toString(),
                "--skip-orphans", "--not-migrated", notMigrated.toString());

        assertThat(exit).isZero();
        java.util.List<String> lines = Files.readAllLines(notMigrated);
        assertThat(lines.get(0)).isEqualTo("# class\tresource\tds\tnote");
        assertThat(lines).contains("AMBIGUOUS\t" + eth0 + "\tifInOctets\t2 live label sets");
        assertThat(lines).contains("ORPHAN_PARTIAL\t" + eth0 + "\tifSpeed\tskipped by --skip-orphans");
        assertThat(lines).hasSize(11); // header + 10 unwritten entries
        assertThat(lines.subList(1, lines.size())).isSorted();
    }

    @Test
    void refusesWhenConfigChangedSinceSnapshot(@TempDir Path tmp) throws Exception {
        Path repo = TestRepos.create(tmp.resolve("rrd"));
        Path labels = tmp.resolve("labels.json");
        new LabelIndex().save(labels);
        new SnapshotInfo(1, 1, null, "0".repeat(64), List.of()).save(SnapshotInfo.sidecarOf(labels));
        Path home = tmp.resolve("opennms");
        Files.createDirectories(home.resolve("etc"));
        Files.writeString(home.resolve("etc/opennms.properties"),
                "org.opennms.timeseries.tin.metatags.tag.node=${node:label}\n");
        int exit = Main.run("plan", "--rrd-dir", repo.toString(), "--labels", labels.toString(),
                "--out", tmp.resolve("plan.json").toString(), "--opennms-home", home.toString(), "--no-rest");
        assertThat(exit).isEqualTo(1);
    }
}
