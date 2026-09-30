/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.backfill;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.no42.rrdlift.plan.EntryClass;
import org.no42.rrdlift.plan.Plan;
import org.no42.rrdlift.plan.PlanEntry;
import org.no42.rrdlift.prom.PromClient;
import org.no42.rrdlift.prom.TimeSeries;
import org.no42.rrdlift.prom.WriteException;
import org.no42.rrdlift.rrd.RrdFile;
import org.no42.rrdlift.rrd.RrdOpener;
import org.no42.rrdlift.samples.Pass;
import org.no42.rrdlift.samples.SampleGenerator;
import org.no42.rrdlift.samples.Series;

/** Writes the planned history pass by pass, file by file, with retries, a rate limit and a checkpoint. */
public final class Backfiller {

    public record Outcome(boolean paused, int done, int failed, long samples, String pauseReason) {}

    /** The backend stayed unavailable through every retry. */
    static final class PauseException extends Exception {
        PauseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final PromClient client;
    private final RrdOpener opener;
    private final Checkpoint checkpoint;
    private final RateLimiter limiter;
    private final int threads;
    private final int batchSamples;
    private final int maxRetries;
    private final RateLimiter.Sleeper backoff;
    private final PrintStream log;

    public Backfiller(PromClient client, RrdOpener opener, Checkpoint checkpoint, RateLimiter limiter, int threads,
                      int batchSamples, int maxRetries, RateLimiter.Sleeper backoff, PrintStream log) {
        this.client = client;
        this.opener = opener;
        this.checkpoint = checkpoint;
        this.limiter = limiter;
        this.threads = threads;
        this.batchSamples = batchSamples;
        this.maxRetries = maxRetries;
        this.backoff = backoff;
        this.log = log;
    }

    public Outcome run(Plan plan, boolean retryFailed) throws IOException, InterruptedException {
        Map<String, List<PlanEntry>> files = plan.writableByFile();
        List<String> order = new ArrayList<>(files.keySet());
        order.sort(Comparator.comparing((String f) ->
                files.get(f).stream().anyMatch(e -> e.entryClass().isOrphan())));
        int done = 0;
        int failed = 0;
        long samples = 0;
        for (Pass pass : List.of(Pass.RECENT, Pass.OLDER)) {
            Map<String, Checkpoint.Entry> state = checkpoint.load(pass.name());
            List<String> todo = order.stream().filter(f -> {
                Checkpoint.Entry e = state.get(f);
                return retryFailed ? e != null && e.status() == Checkpoint.Status.FAILED : e == null;
            }).toList();
            AtomicReference<String> pause = new AtomicReference<>();
            AtomicReference<Throwable> error = new AtomicReference<>();
            ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
            List<Future<Checkpoint.Entry>> futures = new ArrayList<>();
            for (String file : todo) {
                futures.add(pool.submit(() -> {
                    if (pause.get() != null || error.get() != null) {
                        return null;
                    }
                    try {
                        return processFile(file, files.get(file), pass);
                    } catch (PauseException e) {
                        pause.compareAndSet(null, e.getMessage()); // before the next task on this thread starts
                        return null;
                    } catch (Exception e) {
                        if (e instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        error.compareAndSet(null, e); // stop starting files; not a backend outage
                        return null;
                    }
                }));
            }
            pool.shutdown();
            for (Future<Checkpoint.Entry> f : futures) {
                try {
                    Checkpoint.Entry e = f.get();
                    if (e == null) {
                        continue;
                    }
                    if (e.status() == Checkpoint.Status.DONE) {
                        done++;
                        samples += e.samples();
                    } else {
                        failed++;
                        log.println("backfill: FAILED " + e.file() + " (" + pass + "): " + e.error());
                    }
                } catch (ExecutionException ex) {
                    error.compareAndSet(null, ex.getCause()); // an Error escaped the task
                }
            }
            pool.awaitTermination(1, TimeUnit.MINUTES);
            Throwable failure = error.get();
            if (failure != null) {
                String message = failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
                throw new IOException("backfill aborted: " + message, failure);
            }
            log.printf("backfill: pass %s: %d files done, %d failed so far%n", pass, done, failed);
            if (pause.get() != null) {
                log.println("backfill: paused: " + pause.get());
                return new Outcome(true, done, failed, samples, pause.get());
            }
        }
        return new Outcome(false, done, failed, samples, null);
    }

    private Checkpoint.Entry processFile(String file, List<PlanEntry> entries, Pass pass) throws Exception {
        Map<String, Series> byDs = new HashMap<>();
        try {
            RrdFile rrd = opener.open(Path.of(file));
            for (Series s : SampleGenerator.generate(rrd, pass)) {
                byDs.put(s.dsName(), s);
            }
        } catch (IOException | RuntimeException e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return record(file, pass, Checkpoint.Status.FAILED, 0, 1, "read: " + message);
        }
        for (PlanEntry e : entries) {
            if (!byDs.containsKey(e.dsName())) {
                return record(file, pass, Checkpoint.Status.FAILED, 0, 1,
                        "planned data source " + e.dsName() + " missing from file");
            }
        }
        List<TimeSeries> batch = new ArrayList<>();
        int inBatch = 0;
        long written = 0;
        int attempts = 1;
        try {
            for (PlanEntry e : entries) {
                Series s = byDs.get(e.dsName());
                for (int from = 0; from < s.size(); from += batchSamples) {
                    int to = Math.min(s.size(), from + batchSamples);
                    if (inBatch + (to - from) > batchSamples && !batch.isEmpty()) {
                        attempts = Math.max(attempts, send(batch, inBatch));
                        written += inBatch;
                        batch = new ArrayList<>();
                        inBatch = 0;
                    }
                    batch.add(new TimeSeries(e.labels(), Arrays.copyOfRange(s.timesMs(), from, to),
                            Arrays.copyOfRange(s.values(), from, to)));
                    inBatch += to - from;
                }
            }
            if (!batch.isEmpty()) {
                attempts = Math.max(attempts, send(batch, inBatch));
                written += inBatch;
            }
        } catch (WriteException e) {
            return record(file, pass, Checkpoint.Status.FAILED, written, attempts, "HTTP " + e.status() + ": " + e.body());
        }
        return record(file, pass, Checkpoint.Status.DONE, written, attempts, null);
    }

    /** Returns the attempt number that succeeded. Throws WriteException for permanent errors. */
    private int send(List<TimeSeries> batch, int samples) throws WriteException, PauseException, InterruptedException {
        limiter.acquire(samples);
        IOException last = null;
        for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
            try {
                client.write(batch);
                return attempt;
            } catch (WriteException e) {
                if (!e.retryable()) {
                    throw e;
                }
                last = e;
            } catch (IOException e) {
                last = e;
            }
            if (attempt <= maxRetries) {
                backoff.sleepNanos(TimeUnit.SECONDS.toNanos(backoffSeconds(attempt)));
            }
        }
        throw new PauseException("backend unavailable after " + (maxRetries + 1) + " attempts: " + last.getMessage(), last);
    }

    /** 1 s, 2 s, 4 s ... capped at 60 s; the shift is capped first so large attempts cannot overflow. */
    static long backoffSeconds(int attempt) {
        return Math.min(60L, 1L << Math.min(attempt - 1, 6));
    }

    private Checkpoint.Entry record(String file, Pass pass, Checkpoint.Status status, long samples, int attempts,
                                    String error) throws IOException {
        Checkpoint.Entry e = new Checkpoint.Entry(file, pass.name(), status, samples, attempts, error);
        checkpoint.append(e);
        return e;
    }
}
