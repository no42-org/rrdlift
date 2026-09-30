/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.no42.rrdlift.labels.LabelIndex;
import org.no42.rrdlift.plan.Plan;
import org.no42.rrdlift.plan.PlanEntry;
import org.no42.rrdlift.plan.Planner;
import org.no42.rrdlift.prom.FakeBackend;
import org.no42.rrdlift.prom.PromClient;
import org.no42.rrdlift.repo.TestRepos;
import org.no42.rrdlift.rrd.Fixtures;
import org.no42.rrdlift.rrd.NativeRrdtoolReader;
import org.no42.rrdlift.rrd.RrdOpener;

class BackfillerTest {

    static final RrdOpener OPENER = new RrdOpener(RrdOpener.Mode.NATIVE, null);
    static final PrintStream QUIET = new PrintStream(PrintStream.nullOutputStream());

    @TempDir
    Path tmp;
    FakeBackend backend;
    Plan plan;

    @BeforeEach
    void setUp() throws Exception {
        backend = FakeBackend.start();
        plan = Planner.plan(TestRepos.create(tmp.resolve("rrd")), OPENER, new LabelIndex(), null, false);
    }

    @AfterEach
    void tearDown() {
        backend.close();
    }

    Backfiller backfiller(int batchSamples, int maxRetries) {
        return new Backfiller(new PromClient(backend.writeUrl(), null, null), OPENER, new Checkpoint(tmp),
                new RateLimiter(0), 1, batchSamples, maxRetries, n -> { }, QUIET);
    }

    long expectedSamples() {
        return plan.entries().stream().filter(e -> e.labels() != null).mapToLong(PlanEntry::samples).sum();
    }

    void assertEverySampleStored() {
        for (PlanEntry e : plan.entries()) {
            if (e.labels() != null && e.samples() > 0) {
                assertThat(backend.data().get(e.labels())).as(e.dsName()).hasSize((int) e.samples());
            }
        }
    }

    @Test
    void writesEverySampleExactlyOnceAcrossPassesAndBatches() throws Exception {
        Backfiller.Outcome outcome = backfiller(100, 0).run(plan, false);

        assertThat(outcome.paused()).isFalse();
        assertThat(outcome.failed()).isZero();
        assertThat(outcome.samples()).isEqualTo(expectedSamples());
        assertThat(backend.samplesReceived()).isEqualTo(expectedSamples());
        assertEverySampleStored();
        assertThat(new Checkpoint(tmp).load("RECENT")).hasSize(3);
        assertThat(new Checkpoint(tmp).load("OLDER")).hasSize(3);
    }

    @Test
    void skipsFilesAlreadyDone() throws Exception {
        String icmp = plan.entries().stream().filter(e -> "icmp".equals(e.dsName())).findFirst().orElseThrow().file();
        Checkpoint cp = new Checkpoint(tmp);
        cp.append(new Checkpoint.Entry(icmp, "RECENT", Checkpoint.Status.DONE, 0, 1, null));
        cp.append(new Checkpoint.Entry(icmp, "OLDER", Checkpoint.Status.DONE, 0, 1, null));

        backfiller(2000, 0).run(plan, false);

        assertThat(backend.data().keySet()).noneMatch(l -> "icmp".equals(l.get("__name__")));
    }

    @Test
    void pausesWhenBackendStaysDownAndResumesTheInterruptedFile() throws Exception {
        backend.failNextWrites(3, 503);
        Backfiller.Outcome first = backfiller(2000, 1).run(plan, false);
        assertThat(first.paused()).isTrue();
        assertThat(first.pauseReason()).contains("503");
        assertThat(new Checkpoint(tmp).load("RECENT")).isEmpty();

        Backfiller.Outcome second = backfiller(2000, 1).run(plan, false);
        assertThat(second.paused()).isFalse();
        assertEverySampleStored();
        assertThat(new Checkpoint(tmp).load("RECENT").values())
                .anyMatch(e -> e.attempts() == 2)
                .allMatch(e -> e.status() == Checkpoint.Status.DONE);
    }

    @Test
    void permanentErrorFailsTheFileAndRetryFailedRecovers() throws Exception {
        long lastUpdate = NativeRrdtoolReader.read(Fixtures.path("aarch64", "grp.rrd")).lastUpdate();
        backend.rejectOlderThanMs((lastUpdate - 30 * 86400L) * 1000);

        Backfiller.Outcome outcome = backfiller(2000, 0).run(plan, false);
        assertThat(outcome.paused()).isFalse();
        assertThat(outcome.failed()).isPositive();
        Map<String, Checkpoint.Entry> older = new Checkpoint(tmp).load("OLDER");
        assertThat(older.values()).anyMatch(e -> e.status() == Checkpoint.Status.FAILED && e.error().contains("400"));

        backend.rejectOlderThanMs(Long.MIN_VALUE);
        Backfiller.Outcome retry = backfiller(2000, 0).run(plan, true);
        assertThat(retry.failed()).isZero();
        assertEverySampleStored();
    }

    @Test
    void unreadableFileFailsThatFileWithoutPausing() throws Exception {
        String icmp = plan.entries().stream().filter(e -> "icmp".equals(e.dsName())).findFirst().orElseThrow().file();
        try (RandomAccessFile raf = new RandomAccessFile(icmp, "rw")) {
            raf.seek(24);
            raf.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(-1L).array());
        }

        Backfiller.Outcome outcome = backfiller(2000, 0).run(plan, false);

        assertThat(outcome.paused()).isFalse();
        Map<String, Checkpoint.Entry> recent = new Checkpoint(tmp).load("RECENT");
        assertThat(recent.get(icmp).status()).isEqualTo(Checkpoint.Status.FAILED);
        assertThat(recent.get(icmp).error()).startsWith("read: ");
        assertThat(recent.entrySet()).filteredOn(e -> !e.getKey().equals(icmp))
                .allMatch(e -> e.getValue().status() == Checkpoint.Status.DONE);
        assertThat(recent).hasSize(3);
    }

    @Test
    void checkpointWriteFailureAbortsInsteadOfPausing() throws Exception {
        Path stateDir = tmp.resolve("blocked");
        Files.createDirectories(stateDir);
        Files.writeString(stateDir.resolve("state"), "a regular file where the state directory belongs");
        Backfiller b = new Backfiller(new PromClient(backend.writeUrl(), null, null), OPENER, new Checkpoint(stateDir),
                new RateLimiter(0), 1, 2000, 0, n -> { }, QUIET);

        assertThatThrownBy(() -> b.run(plan, false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("backfill aborted");

        int firstFileEntries = plan.writableByFile().values().iterator().next().size();
        assertThat(backend.writeCount()).isPositive().isLessThanOrEqualTo(firstFileEntries);
    }

    @Test
    void backoffDoublesFromOneSecondAndCapsAtSixtySeconds() throws Exception {
        List<Long> sleeps = new ArrayList<>();
        backend.failNextWrites(71, 503);
        Backfiller b = new Backfiller(new PromClient(backend.writeUrl(), null, null), OPENER, new Checkpoint(tmp),
                new RateLimiter(0), 1, 2000, 70, sleeps::add, QUIET);

        assertThat(b.run(plan, false).paused()).isTrue();

        assertThat(sleeps).hasSize(70);
        assertThat(sleeps.subList(0, 8).stream().map(n -> n / 1_000_000_000L))
                .containsExactly(1L, 2L, 4L, 8L, 16L, 32L, 60L, 60L);
        assertThat(sleeps).allMatch(n -> n > 0 && n <= 60_000_000_000L);
        assertThat(sleeps.get(69)).isEqualTo(60_000_000_000L);
    }

    @Test
    void plannedDataSourceMissingFromFileFailsTheFile() throws Exception {
        PlanEntry icmp = plan.entries().stream().filter(e -> "icmp".equals(e.dsName())).findFirst().orElseThrow();
        List<PlanEntry> entries = new ArrayList<>(plan.entries());
        entries.set(entries.indexOf(icmp), new PlanEntry(icmp.file(), icmp.resourceId(), "gone", icmp.mtype(),
                icmp.entryClass(), icmp.labels(), icmp.samples(), icmp.note()));
        Plan changed = new Plan(plan.rrdDir(), plan.createdAt(), plan.oldestSampleSec(), entries);

        Backfiller.Outcome outcome = backfiller(2000, 0).run(changed, false);

        assertThat(outcome.failed()).isPositive();
        for (String pass : List.of("RECENT", "OLDER")) {
            Checkpoint.Entry e = new Checkpoint(tmp).load(pass).get(icmp.file());
            assertThat(e.status()).isEqualTo(Checkpoint.Status.FAILED);
            assertThat(e.error()).isEqualTo("planned data source gone missing from file");
        }
    }

    static Plan relabel(Plan p, String ds, String key, String value) {
        List<PlanEntry> entries = p.entries().stream().map(e -> {
            if (!ds.equals(e.dsName()) || e.labels() == null) {
                return e;
            }
            Map<String, String> labels = new TreeMap<>(e.labels());
            labels.put(key, value);
            return new PlanEntry(e.file(), e.resourceId(), e.dsName(), e.mtype(), e.entryClass(), labels, e.samples(),
                    e.note(), e.labelSources(), e.candidates());
        }).toList();
        return new Plan(p.rrdDir(), p.createdAt(), p.oldestSampleSec(), entries, p.snapshot());
    }

    @Test
    void rewritesOnlyFilesWhoseLabelsChanged() throws Exception {
        backfiller(2000, 0).run(plan, false);
        Plan changed = relabel(plan, "icmp", "node", "renamed");
        backfiller(2000, 0).run(changed, false);

        PlanEntry icmp = changed.entries().stream().filter(e -> "icmp".equals(e.dsName())).findFirst().orElseThrow();
        assertThat(backend.data().get(icmp.labels())).hasSize((int) icmp.samples());
        List<String> recent = Files.readAllLines(tmp.resolve("state/RECENT.jsonl"));
        assertThat(recent).filteredOn(l -> l.contains(icmp.file())).hasSize(2);
        String grpFile = plan.entries().stream().filter(e -> "ifSpeed".equals(e.dsName())).findFirst().orElseThrow().file();
        assertThat(recent).filteredOn(l -> l.contains(grpFile)).hasSize(1);
    }
}
