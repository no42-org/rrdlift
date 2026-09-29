/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import org.no42.rrdlift.backfill.Checkpoint;
import org.no42.rrdlift.plan.Plan;
import org.no42.rrdlift.verify.Verifier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "verify", mixinStandardHelpOptions = true,
        description = "Reads backfilled history back and compares it with the RRD files. Exit 1 on any mismatch.")
public final class VerifyCommand implements Callable<Integer> {

    private static final int USAGE = 64;

    @Option(names = "--plan", required = true)
    Path plan;

    @Mixin
    Connection connection;

    @Mixin
    ReaderOptions readerOptions;

    @Option(names = "--state-dir", defaultValue = ".")
    Path stateDir;

    @Option(names = "--sample", defaultValue = "1", description = "Percent of files to check (plus every retried file)")
    double percent;

    @Option(names = "--all", description = "Check every file")
    boolean all;

    @Option(names = "--seed", defaultValue = "1")
    long seed;

    @Option(names = "--tolerance", defaultValue = "0", description = "Relative tolerance; 0 means exact")
    double tolerance;

    @Override
    public Integer call() {
        if (connection.readUrl == null) {
            System.err.println("verify: --read-url is required");
            return USAGE;
        }
        try {
            Plan p = Plan.load(plan);
            List<String> files = Verifier.select(p, new Checkpoint(stateDir), all, percent, seed);
            Verifier.Report r = new Verifier(connection.client(), readerOptions.opener(), tolerance).verify(p, files);
            r.mismatches().stream().limit(20).forEach(m -> System.out.printf(
                    "MISMATCH %s %s at %d: %s expected=%s actual=%s%n",
                    m.file(), m.dsName(), m.timeMs(), m.kind(), m.expected(), m.actual()));
            System.out.printf("verify: %d files, %d series, %d samples, %d mismatches%n",
                    r.files(), r.series(), r.samples(), r.mismatches().size());
            return r.mismatches().isEmpty() ? 0 : 1;
        } catch (Exception e) {
            System.err.println("verify: " + e.getMessage());
            return 1;
        }
    }
}
