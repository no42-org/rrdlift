/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.preflight;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.no42.rrdlift.prom.PromClient;
import org.no42.rrdlift.prom.Selectors;
import org.no42.rrdlift.prom.TimeSeries;

/** Proves the backend accepts, stores and returns a sample as old as the oldest RRD sample. */
public final class Preflight {

    public record Result(List<String> failures) {
        public boolean ok() {
            return failures.isEmpty();
        }
    }

    private Preflight() {}

    public static Result run(PromClient client, long oldestSec, long nowSec) {
        return run(client, oldestSec, nowSec, 5, 1000);
    }

    public static Result run(PromClient client, long oldestSec, long nowSec, int readAttempts, long readDelayMs) {
        List<String> failures = new ArrayList<>();
        Map<String, String> probe = Map.of("__name__", "rrdlift_probe", "run", UUID.randomUUID().toString());
        String selector = Selectors.exact(probe);
        long days = (nowSec - oldestSec) / 86400;

        try {
            client.write(List.of(new TimeSeries(probe, new long[] {nowSec * 1000}, new double[] {1})));
        } catch (IOException e) {
            failures.add("remote write of a current sample failed (" + e.getMessage() + "). Check --write-url, "
                    + "that the remote-write receiver is enabled (Prometheus: --web.enable-remote-write-receiver) "
                    + "and --org-id for multi-tenant backends.");
            return new Result(failures);
        }

        boolean oldWritten = true;
        try {
            client.write(List.of(new TimeSeries(probe, new long[] {oldestSec * 1000}, new double[] {2})));
        } catch (IOException e) {
            oldWritten = false;
            failures.add("the backend rejected a sample from " + Instant.ofEpochSecond(oldestSec) + " (" + e.getMessage()
                    + "). Prometheus, Mimir, Cortex: set out_of_order_time_window longer than " + days
                    + " days and retention longer than that.");
        }

        if (!readsBack(client, selector, nowSec, 1, readAttempts, readDelayMs)) {
            failures.add("a sample written now cannot be read back through --read-url /api/v1/query.");
        }
        if (oldWritten && !readsBack(client, selector, oldestSec, 2, readAttempts, readDelayMs)) {
            failures.add("a sample from " + Instant.ofEpochSecond(oldestSec) + " was accepted but cannot be read back. "
                    + "Check retention (VictoriaMetrics drops samples older than -retentionPeriod silently) "
                    + "and the query cache (VictoriaMetrics: -search.disableCache during the backfill).");
        }
        try {
            if (client.series(selector, oldestSec, nowSec).isEmpty()) {
                failures.add("the probe series is missing from /api/v1/series.");
            }
        } catch (IOException e) {
            failures.add("/api/v1/series failed: " + e.getMessage());
        }
        return new Result(failures);
    }

    private static boolean readsBack(PromClient client, String selector, long timeSec, double expected,
                                     int attempts, long delayMs) {
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                if (client.query(selector, timeSec).stream()
                        .anyMatch(r -> r.values().length > 0 && r.values()[r.values().length - 1] == expected)) {
                    return true;
                }
            } catch (IOException e) {
                // treated like "not visible yet"
            }
            if (attempt < attempts) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }
}
