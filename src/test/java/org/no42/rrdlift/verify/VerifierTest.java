/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.verify;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.no42.rrdlift.Main;
import org.no42.rrdlift.backfill.Backfiller;
import org.no42.rrdlift.backfill.Checkpoint;
import org.no42.rrdlift.backfill.RateLimiter;
import org.no42.rrdlift.labels.LabelIndex;
import org.no42.rrdlift.plan.Plan;
import org.no42.rrdlift.plan.PlanEntry;
import org.no42.rrdlift.plan.Planner;
import org.no42.rrdlift.prom.FakeBackend;
import org.no42.rrdlift.prom.PromClient;
import org.no42.rrdlift.repo.TestRepos;
import org.no42.rrdlift.rrd.RrdOpener;

class VerifierTest {

    static final RrdOpener OPENER = new RrdOpener(RrdOpener.Mode.NATIVE, null);

    @TempDir
    Path tmp;
    FakeBackend backend;
    PromClient client;
    Plan plan;

    @BeforeEach
    void backfill() throws Exception {
        backend = FakeBackend.start();
        client = new PromClient(backend.writeUrl(), backend.readUrl(), null);
        plan = Planner.plan(TestRepos.create(tmp.resolve("rrd")), OPENER, new LabelIndex(), null, false);
        new Backfiller(client, OPENER, new Checkpoint(tmp), new RateLimiter(0), 2, 2000, 0, n -> { },
                new PrintStream(PrintStream.nullOutputStream())).run(plan, false);
    }

    @AfterEach
    void stop() {
        backend.close();
    }

    List<String> allFiles() {
        return List.copyOf(plan.writableByFile().keySet());
    }

    TreeMap<Long, Double> stored(String dsName) {
        PlanEntry e = plan.entries().stream().filter(p -> dsName.equals(p.dsName())).findFirst().orElseThrow();
        return backend.data().get(e.labels());
    }

    @Test
    void cleanBackfillVerifies() {
        Verifier.Report r = new Verifier(client, OPENER, 0).verify(plan, allFiles());
        assertThat(r.mismatches()).isEmpty();
        assertThat(r.files()).isEqualTo(3);
        assertThat(r.samples()).isEqualTo(plan.entries().stream()
                .filter(e -> e.labels() != null).mapToLong(PlanEntry::samples).sum());
    }

    @Test
    void detectsChangedGaugeAndMissingCounterSample() {
        TreeMap<Long, Double> speed = stored("ifSpeed");
        Map.Entry<Long, Double> first = speed.firstEntry();
        speed.put(first.getKey(), first.getValue() + 1);
        TreeMap<Long, Double> errors = stored("ifInErrors");
        errors.remove(errors.lastKey());

        Verifier.Report r = new Verifier(client, OPENER, 0).verify(plan, allFiles());
        assertThat(r.mismatches()).extracting(Verifier.Mismatch::kind).contains("value", "missing");
        assertThat(r.mismatches()).filteredOn(m -> m.kind().equals("value")).singleElement()
                .extracting(Verifier.Mismatch::dsName).isEqualTo("ifSpeed");
    }

    @Test
    void ignoresSeriesWithExtraLabels() throws Exception {
        PlanEntry e = plan.entries().stream().filter(p -> "icmp".equals(p.dsName())).findFirst().orElseThrow();
        Map<String, String> superset = new TreeMap<>(e.labels());
        superset.put("node", "live");
        TreeMap<Long, Double> conflicting = new TreeMap<>();
        backend.data().get(e.labels()).keySet().forEach(t -> conflicting.put(t, -1.0));
        backend.data().put(superset, conflicting);

        assertThat(new Verifier(client, OPENER, 0).verify(plan, allFiles()).mismatches()).isEmpty();
    }

    @Test
    void selectionIncludesRetriedFiles() throws Exception {
        String icmp = plan.entries().stream().filter(p -> "icmp".equals(p.dsName())).findFirst().orElseThrow().file();
        Checkpoint cp = new Checkpoint(tmp);
        cp.append(new Checkpoint.Entry(icmp, "OLDER", Checkpoint.Status.DONE, 1, 3, null));
        assertThat(Verifier.select(plan, cp, false, 0, 1)).contains(icmp);
        assertThat(Verifier.select(plan, cp, true, 0, 1)).containsExactlyInAnyOrderElementsOf(allFiles());
    }

    @Test
    void commandExitsNonZeroOnMismatch() throws Exception {
        Path planFile = tmp.resolve("plan.json");
        plan.save(planFile);
        String[] args = {"verify", "--plan", planFile.toString(), "--read-url", backend.readUrl().toString(),
                "--state-dir", tmp.toString(), "--all"};
        assertThat(Main.run(args)).isZero();
        TreeMap<Long, Double> speed = stored("ifSpeed");
        speed.put(speed.firstKey(), -42.0);
        assertThat(Main.run(args)).isEqualTo(1);
    }

    @Test
    void corruptFileBecomesReadMismatchAndVerificationContinues() throws Exception {
        String icmp = plan.entries().stream().filter(p -> "icmp".equals(p.dsName())).findFirst().orElseThrow().file();
        try (RandomAccessFile raf = new RandomAccessFile(icmp, "rw")) {
            raf.seek(24);
            raf.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(-1L).array());
        }
        Verifier.Report r = new Verifier(client, OPENER, 0).verify(plan, allFiles());
        assertThat(r.mismatches()).filteredOn(m -> m.kind().equals("read")).isNotEmpty()
                .allMatch(m -> m.file().equals(icmp));
        assertThat(r.files()).isEqualTo(3);
    }
}
