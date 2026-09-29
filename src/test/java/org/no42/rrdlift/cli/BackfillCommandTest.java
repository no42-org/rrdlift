/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.no42.rrdlift.Main;
import org.no42.rrdlift.labels.LabelIndex;
import org.no42.rrdlift.plan.Planner;
import org.no42.rrdlift.prom.FakeBackend;
import org.no42.rrdlift.repo.TestRepos;
import org.no42.rrdlift.rrd.RrdOpener;

class BackfillCommandTest {

    @Test
    void exitCodesReflectOutcome(@TempDir Path tmp) throws Exception {
        Path planFile = tmp.resolve("plan.json");
        Planner.plan(TestRepos.create(tmp.resolve("rrd")), new RrdOpener(RrdOpener.Mode.NATIVE, null),
                new LabelIndex(), null, false).save(planFile);
        try (FakeBackend backend = FakeBackend.start()) {
            backend.failNextWrites(100, 500);
            assertThat(Main.run("backfill", "--plan", planFile.toString(), "--write-url", backend.writeUrl().toString(),
                    "--state-dir", tmp.toString(), "--max-retries", "0", "--rate", "0")).isEqualTo(2);
            backend.failNextWrites(0, 500);
            assertThat(Main.run("backfill", "--plan", planFile.toString(), "--write-url", backend.writeUrl().toString(),
                    "--state-dir", tmp.toString(), "--rate", "0")).isZero();
            assertThat(backend.data()).isNotEmpty();
        }
    }
}
