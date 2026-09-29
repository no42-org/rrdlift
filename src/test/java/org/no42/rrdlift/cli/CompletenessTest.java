/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.no42.rrdlift.Main;
import org.no42.rrdlift.backfill.Checkpoint;
import org.no42.rrdlift.labels.LabelIndex;
import org.no42.rrdlift.prom.FakeBackend;
import org.no42.rrdlift.repo.TestRepos;
import org.no42.rrdlift.rrd.Fixtures;
import org.no42.rrdlift.rrd.NativeRrdtoolReader;

/** A migration passes verify only when every planned file is done in both passes. */
class CompletenessTest {

    record Run(int exit, String out) {}

    static Run run(String... args) {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            return new Run(Main.run(args), buf.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(original);
        }
    }

    @Test
    void verifyFailsUntilEveryFileIsDoneInBothPasses(@TempDir Path tmp) throws Exception {
        Path repo = TestRepos.create(tmp.resolve("rrd"));
        Path labels = tmp.resolve("labels.json");
        new LabelIndex().save(labels);
        Path plan = tmp.resolve("plan.json");
        assertThat(run("plan", "--rrd-dir", repo.toString(), "--labels", labels.toString(), "--out", plan.toString(),
                "--orphans", tmp.resolve("orphans.txt").toString(),
                "--not-migrated", tmp.resolve("not-migrated.txt").toString()).exit()).isZero();

        long lastUpdate = NativeRrdtoolReader.read(Fixtures.path("aarch64", "grp.rrd")).lastUpdate();
        try (FakeBackend backend = FakeBackend.start()) {
            String[] backfill = {"backfill", "--plan", plan.toString(), "--write-url", backend.writeUrl().toString(),
                    "--state-dir", tmp.toString(), "--max-retries", "0", "--rate", "0"};
            String[] verify = {"verify", "--plan", plan.toString(), "--read-url", backend.readUrl().toString(),
                    "--state-dir", tmp.toString()};

            backend.rejectOlderThanMs((lastUpdate - 30 * 86400L) * 1000); // the OLDER pass fails
            Run first = run(backfill);
            assertThat(first.exit()).isEqualTo(1);
            List<String> failed = new Checkpoint(tmp).load("OLDER").values().stream()
                    .filter(e -> e.status() == Checkpoint.Status.FAILED).map(Checkpoint.Entry::file).sorted().toList();
            assertThat(failed).isNotEmpty();
            assertThat(new Checkpoint(tmp).load("OLDER").values())
                    .filteredOn(e -> e.status() == Checkpoint.Status.FAILED).allMatch(e -> e.attempts() == 1);

            Run rerun = run(backfill);
            assertThat(rerun.exit()).isEqualTo(1);
            assertThat(first.out()).contains("backfill: " + (3 - failed.size()) + " of 3 files done in both passes, "
                    + failed.size() + " failed");
            assertThat(rerun.out()).contains("of 3 files done in both passes");

            Run check = run(verify);
            assertThat(check.exit()).isEqualTo(1);
            assertThat(check.out()).contains("verify: 3 files planned, " + (3 - failed.size())
                    + " done in both passes, " + failed.size() + " not done");
            assertThat(check.out()).contains(failed);

            backend.rejectOlderThanMs(Long.MIN_VALUE);
            String[] retry = java.util.Arrays.copyOf(backfill, backfill.length + 1);
            retry[backfill.length] = "--retry-failed";
            Run retried = run(retry);
            assertThat(retried.exit()).isZero();
            assertThat(retried.out()).contains("backfill: 3 of 3 files done in both passes, 0 failed");

            Run passed = run(verify);
            assertThat(passed.exit()).isZero();
            assertThat(passed.out()).contains("verify: 3 files planned, 3 done in both passes, 0 not done");
        }
    }
}
