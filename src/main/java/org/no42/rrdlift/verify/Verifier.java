/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.verify;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.no42.rrdlift.backfill.Checkpoint;
import org.no42.rrdlift.plan.Plan;
import org.no42.rrdlift.plan.PlanEntry;
import org.no42.rrdlift.prom.PromClient;
import org.no42.rrdlift.prom.Selectors;
import org.no42.rrdlift.rrd.RrdFile;
import org.no42.rrdlift.rrd.RrdOpener;
import org.no42.rrdlift.samples.Pass;
import org.no42.rrdlift.samples.SampleGenerator;
import org.no42.rrdlift.samples.Series;

/** Regenerates samples from the RRD files and compares them with what the backend returns. */
public final class Verifier {

    public record Mismatch(String file, String dsName, long timeMs, String kind, double expected, double actual) {}

    public record Report(int files, int series, long samples, List<Mismatch> mismatches) {}

    private final PromClient client;
    private final RrdOpener opener;
    private final double tolerance;

    public Verifier(PromClient client, RrdOpener opener, double tolerance) {
        this.client = client;
        this.opener = opener;
        this.tolerance = tolerance;
    }

    public static List<String> select(Plan plan, Checkpoint checkpoint, boolean all, double percent, long seed)
            throws IOException {
        List<String> files = new ArrayList<>(plan.writableByFile().keySet());
        if (all) {
            return files;
        }
        Set<String> known = new java.util.HashSet<>(files);
        Set<String> selected = new LinkedHashSet<>();
        for (String pass : List.of(Pass.RECENT.name(), Pass.OLDER.name())) {
            checkpoint.load(pass).values().stream().filter(e -> e.attempts() > 1).map(Checkpoint.Entry::file)
                    .filter(known::contains).forEach(selected::add);
        }
        Collections.shuffle(files, new Random(seed));
        int n = files.isEmpty() ? 0
                : Math.min(files.size(), Math.max(1, (int) Math.ceil(files.size() * percent / 100)));
        selected.addAll(files.subList(0, n));
        return List.copyOf(selected);
    }

    public Report verify(Plan plan, Collection<String> files) {
        Map<String, List<PlanEntry>> byFile = plan.writableByFile();
        List<Mismatch> mismatches = new ArrayList<>();
        int seriesCount = 0;
        long samples = 0;
        for (String file : files) {
            RrdFile rrd;
            Map<String, Series> byDs = new HashMap<>();
            try {
                rrd = opener.open(Path.of(file));
                for (Series s : SampleGenerator.generate(rrd, Pass.ALL)) {
                    byDs.put(s.dsName(), s);
                }
            } catch (IOException | RuntimeException e) {
                mismatches.add(new Mismatch(file, null, 0, "read", Double.NaN, Double.NaN));
                continue;
            }
            for (PlanEntry entry : byFile.getOrDefault(file, List.of())) {
                Series s = byDs.get(entry.dsName());
                if (s == null || s.size() == 0) {
                    continue;
                }
                seriesCount++;
                samples += s.size();
                Map<Long, Double> actual;
                try {
                    actual = readBack(entry.labels(), s.timesMs()[0] / 1000, rrd.lastUpdate());
                } catch (IOException | RuntimeException e) {
                    mismatches.add(new Mismatch(file, entry.dsName(), 0, "read", Double.NaN, Double.NaN));
                    continue;
                }
                compare(file, s, actual, mismatches);
            }
        }
        return new Report(files.size(), seriesCount, samples, List.copyOf(mismatches));
    }

    private Map<Long, Double> readBack(Map<String, String> labels, long firstSec, long lastUpdate) throws IOException {
        String query = Selectors.exact(labels) + "[" + (lastUpdate - firstSec + 1) + "s]";
        Map<Long, Double> out = new HashMap<>();
        for (PromClient.QueryResult r : client.query(query, lastUpdate)) {
            if (!r.metric().equals(labels)) {
                continue; // a series with extra labels, not ours
            }
            for (int i = 0; i < r.timesMs().length; i++) {
                out.put(r.timesMs()[i], r.values()[i]);
            }
        }
        return out;
    }

    private void compare(String file, Series s, Map<Long, Double> actual, List<Mismatch> mismatches) {
        for (int i = 0; i < s.size(); i++) {
            long t = s.timesMs()[i];
            Double a = actual.get(t);
            if (a == null) {
                mismatches.add(new Mismatch(file, s.dsName(), t, "missing", s.values()[i], Double.NaN));
                continue;
            }
            if (!s.counter() && !close(s.values()[i], a)) {
                mismatches.add(new Mismatch(file, s.dsName(), t, "value", s.values()[i], a));
            }
            if (s.counter() && i > 0) {
                Double prev = actual.get(s.timesMs()[i - 1]);
                if (prev != null) {
                    double expected = s.values()[i] - s.values()[i - 1];
                    double got = a - prev;
                    double magnitude = Math.max(Math.max(Math.abs(a), Math.abs(prev)),
                            Math.max(Math.abs(s.values()[i]), Math.abs(s.values()[i - 1])));
                    if (Math.abs(got - expected) > tolerance * magnitude) {
                        mismatches.add(new Mismatch(file, s.dsName(), t, "delta", expected, got));
                    }
                }
            }
        }
    }

    private boolean close(double expected, double actual) {
        return expected == actual || Math.abs(expected - actual) <= tolerance * Math.max(Math.abs(expected), Math.abs(actual));
    }
}
