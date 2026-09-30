/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.no42.rrdlift.plan.EntryClass;
import org.no42.rrdlift.plan.Plan;
import org.no42.rrdlift.plan.PlanEntry;
import org.no42.rrdlift.prom.FakeBackend;
import org.no42.rrdlift.repo.TestRepos;

/** The workflow's last step: after a backfill, a new snapshot must not mistake rrdlift's own writes for live series. */
class ResnapshotTest {

    static Map<String, PlanEntry> byName(Plan plan) {
        return plan.entries().stream().filter(e -> e.dsName() != null)
                .collect(Collectors.toMap(e -> e.resourceId() + "|" + e.dsName(), Function.identity()));
    }

    @Test
    void orphanWrittenInRunOneIsStillAnOrphanInRunTwo(@TempDir Path tmp) throws Exception {
        Path repo = TestRepos.create(tmp.resolve("rrd"));
        Path home = tmp.resolve("opennms");
        Files.createDirectories(home.resolve("etc"));
        Files.writeString(home.resolve("etc/opennms.properties"),
                "org.opennms.timeseries.tin.metatags.tag.node=${node:label}\n");
        Path labels = tmp.resolve("labels.json");
        Path plan = tmp.resolve("plan.json");
        try (FakeBackend backend = FakeBackend.start()) {
            String read = backend.readUrl().toString();
            // a window wide enough to reach back to the fixture dates, so only the cutover can exclude rrdlift's writes
            String[] snapshot = {"snapshot-labels", "--rrd-dir", repo.toString(), "--read-url", read, "--out",
                    labels.toString(), "--opennms-home", home.toString(), "--since-days", "36500"};
            String[] planArgs = {"plan", "--no-pending", "--no-rest", "--rrd-dir", repo.toString(), "--labels",
                    labels.toString(), "--out", plan.toString(), "--opennms-home", home.toString(),
                    "--orphans", tmp.resolve("orphans.txt").toString(),
                    "--not-migrated", tmp.resolve("not-migrated.txt").toString()};

            // run 1: nothing is live, so every data source is a partial orphan (the node label is unknown)
            assertThat(CompletenessTest.run(snapshot).exit()).isZero();
            assertThat(CompletenessTest.run(planArgs).exit()).isZero();
            assertThat(byName(Plan.load(plan)).values()).allMatch(e -> e.entryClass() == EntryClass.ORPHAN_PARTIAL);
            assertThat(CompletenessTest.run("backfill", "--plan", plan.toString(), "--write-url",
                    backend.writeUrl().toString(), "--read-url", read, "--state-dir", tmp.toString(),
                    "--opennms-home", home.toString(), "--max-retries", "0", "--rate", "0").exit()).isZero();
            assertThat(backend.data()).isNotEmpty();

            // run 2: only rrdlift's own series exist in the backend
            assertThat(CompletenessTest.run(snapshot).exit()).isZero();
            assertThat(CompletenessTest.run(planArgs).exit()).isZero();
            Plan second = Plan.load(plan);
            assertThat(second.entries()).noneMatch(e -> e.entryClass() == EntryClass.MATCHED);
            assertThat(byName(second).values()).allMatch(e -> e.entryClass() == EntryClass.ORPHAN_PARTIAL
                    && e.note().contains("unresolved: node"));
        }
    }
}
