/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.no42.rrdlift.backfill.Backfiller;
import org.no42.rrdlift.backfill.Checkpoint;
import org.no42.rrdlift.backfill.RateLimiter;
import org.no42.rrdlift.plan.Plan;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "backfill", mixinStandardHelpOptions = true,
        description = "Writes the planned RRD history to the backend; resumable. Exit 0 done, 1 files failed, 2 paused.")
public final class BackfillCommand implements Callable<Integer> {

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

    @Override
    public Integer call() {
        try {
            if (connection.writeUrl == null) {
                System.err.println("backfill: --write-url is required");
                return 1;
            }
            Backfiller backfiller = new Backfiller(connection.client(), readerOptions.opener(), new Checkpoint(stateDir),
                    new RateLimiter(rate), threads, batchSamples, maxRetries, TimeUnit.NANOSECONDS::sleep, System.out);
            Backfiller.Outcome o = backfiller.run(Plan.load(plan), retryFailed);
            System.out.printf("backfill: %d files done, %d failed, %d samples written%s%n", o.done(), o.failed(),
                    o.samples(), o.paused() ? ", PAUSED (rerun the same command to resume)" : "");
            return o.paused() ? 2 : o.failed() > 0 ? 1 : 0;
        } catch (Exception e) {
            System.err.println("backfill: " + e.getMessage());
            return 1;
        }
    }
}
