/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;
import org.no42.rrdlift.preflight.Preflight;
import org.no42.rrdlift.repo.RepositoryWalker;
import org.no42.rrdlift.repo.WorkItem;
import org.no42.rrdlift.rrd.RrdOpener;
import org.no42.rrdlift.samples.SampleGenerator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "preflight", mixinStandardHelpOptions = true,
        description = "Checks that the backend accepts and returns samples as old as the RRD history.")
public final class PreflightCommand implements Callable<Integer> {

    @Option(names = "--rrd-dir", defaultValue = "/opt/opennms/share/rrd")
    Path rrdDir;

    @Option(names = "--oldest-epoch", description = "Oldest timestamp to probe (seconds); skips the repository scan")
    Long oldestEpoch;

    @Mixin
    Connection connection;

    @Mixin
    ReaderOptions readerOptions;

    @Override
    public Integer call() {
        try {
            long now = System.currentTimeMillis() / 1000;
            long oldest = oldestEpoch != null ? oldestEpoch : scan(readerOptions.opener());
            if (oldest == Long.MAX_VALUE) {
                System.err.println("preflight: no RRD data found under " + rrdDir);
                return 1;
            }
            Preflight.Result result = Preflight.run(connection.client(), oldest, now);
            result.failures().forEach(f -> System.err.println("FAIL: " + f));
            if (result.ok()) {
                System.out.println("preflight: OK, the backend accepts and returns samples back to "
                        + Instant.ofEpochSecond(oldest));
                return 0;
            }
            return 1;
        } catch (Exception e) {
            System.err.println("preflight: " + e.getMessage());
            return 1;
        }
    }

    private long scan(RrdOpener opener) throws IOException {
        long oldest = Long.MAX_VALUE;
        for (WorkItem item : RepositoryWalker.walk(rrdDir).items()) {
            try {
                oldest = Math.min(oldest, SampleGenerator.oldestRowTime(opener.open(item.file())));
            } catch (IOException e) {
                System.err.println("preflight: skipping " + item.file() + ": " + e.getMessage());
            }
        }
        return oldest;
    }
}
