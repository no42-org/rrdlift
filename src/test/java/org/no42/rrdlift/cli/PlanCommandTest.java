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
import org.no42.rrdlift.labels.LabelIndex;
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

        int exit = Main.run("plan", "--rrd-dir", repo.toString(), "--labels", labels.toString(),
                "--out", plan.toString(), "--orphans", orphans.toString());

        assertThat(exit).isZero();
        assertThat(Plan.load(plan).entries()).hasSize(10);
        assertThat(Files.readAllLines(orphans)).containsExactly(
                "response/10.0.0.1/icmp", "snmp/1/eth0-0011/mib2-interfaces", "snmp/fs/fs1/fid1/node-empty");
    }
}
