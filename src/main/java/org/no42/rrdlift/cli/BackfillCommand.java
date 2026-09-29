/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.cli;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.no42.rrdlift.backfill.Backfiller;
import org.no42.rrdlift.backfill.Checkpoint;
import org.no42.rrdlift.backfill.RateLimiter;
import org.no42.rrdlift.plan.Plan;
import org.no42.rrdlift.samples.Pass;
import org.no42.rrdlift.verify.Verifier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "backfill", mixinStandardHelpOptions = true,
        description = "Writes the planned RRD history to the backend; resumable. Exit 0 every file done in both passes, 1 not all done, 2 paused.")
public final class BackfillCommand implements Callable<Integer> {

    private static final int USAGE = 64;

    @Option(names = "--plan", required = true)
    Path plan;

    @Mixin
    Connection connection;

    @Mixin
    ReaderOptions readerOptions;

    @Option(names = "--rate", defaultValue = "20000", description = "Samples per second (0 = unlimited)")
    double rate;

    @Option(names = "--threads", defaultValue = "4")
    int threads;

    @Option(names = "--batch-samples", defaultValue = "2000")
    int batchSamples;

    @Option(names = "--max-retries", defaultValue = "5")
    int maxRetries;

    @Option(names = "--retry-failed", description = "Only retry files whose last attempt failed")
    boolean retryFailed;

    @Option(names = "--state-dir", defaultValue = ".", description = "Directory for state/<pass>.jsonl")
    Path stateDir;

    private static boolean isFailed(Checkpoint.Entry e) {
        return e != null && e.status() == Checkpoint.Status.FAILED;
    }

    @Override
    public Integer call() {
        try {
            if (connection.writeUrl == null) {
                System.err.println("backfill: --write-url is required");
                return USAGE;
            }
            if (batchSamples < 1) {
                System.err.println("backfill: --batch-samples must be at least 1");
                return USAGE;
            }
            if (maxRetries < 0) {
                System.err.println("backfill: --max-retries must be 0 or more");
                return USAGE;
            }
            if (threads < 1) {
                System.err.println("backfill: --threads must be at least 1");
                return USAGE;
            }
            Checkpoint checkpoint = new Checkpoint(stateDir);
            Backfiller backfiller = new Backfiller(connection.client(), readerOptions.opener(), checkpoint,
                    new RateLimiter(rate), threads, batchSamples, maxRetries, TimeUnit.NANOSECONDS::sleep, System.out);
            Plan p = Plan.load(plan);
            Backfiller.Outcome o = backfiller.run(p, retryFailed);
            if (o.paused()) {
                System.out.printf("backfill: %d files done, %d failed, %d samples written, "
                        + "PAUSED (rerun the same command to resume)%n", o.done(), o.failed(), o.samples());
                return 2;
            }
            Verifier.Completeness c = Verifier.completeness(p, checkpoint);
            Map<String, Checkpoint.Entry> recent = checkpoint.load(Pass.RECENT.name());
            Map<String, Checkpoint.Entry> older = checkpoint.load(Pass.OLDER.name());
            long failed = c.notDone().stream().filter(f -> isFailed(recent.get(f)) || isFailed(older.get(f))).count();
            System.out.printf("backfill: %d of %d files done in both passes, %d failed, %d samples written this run%n",
                    c.writableFiles() - c.notDone().size(), c.writableFiles(), failed, o.samples());
            if (failed > 0) {
                System.out.println("backfill: rerun with --retry-failed to retry the failed files");
            }
            return c.notDone().isEmpty() ? 0 : 1;
        } catch (Exception e) {
            System.err.println("backfill: " + e.getMessage());
            return 1;
        }
    }
}
