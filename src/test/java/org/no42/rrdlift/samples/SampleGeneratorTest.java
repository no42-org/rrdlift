/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.samples;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.no42.rrdlift.rrd.Archive;
import org.no42.rrdlift.rrd.DataSource;
import org.no42.rrdlift.rrd.DumpXmlParser;
import org.no42.rrdlift.rrd.Fixtures;
import org.no42.rrdlift.rrd.RrdFile;

class SampleGeneratorTest {

    static final double NaN = Double.NaN;
    static final long L = 1_800_000; // multiple of 300 and 3600

    static Map<Long, Double> points(Series s) {
        Map<Long, Double> m = new TreeMap<>();
        for (int i = 0; i < s.size(); i++) {
            m.put(s.timesMs()[i] / 1000, s.values()[i]);
        }
        return m;
    }

    /** A1: pdp 1, 4 rows ending at L. A2: AVERAGE pdp 12, 3 rows. A3: MAX pdp 12 (ignored). */
    static RrdFile synthetic(String type, double lastValue, double pdpValue, long lastUpdate,
                             double[] a1, double[] a2) {
        DataSource ds = new DataSource("x", type, 600, 0, NaN, lastValue, pdpValue, 0);
        return new RrdFile("0003", 300, lastUpdate, List.of(ds), List.of(
                new Archive("AVERAGE", 1, 0.5, column(a1)),
                new Archive("AVERAGE", 12, 0.5, column(a2)),
                new Archive("MAX", 12, 0.5, column(new double[] {99, 99, 99}))));
    }

    static double[][] column(double[] v) {
        double[][] rows = new double[v.length][];
        for (int i = 0; i < v.length; i++) {
            rows[i] = new double[] {v[i]};
        }
        return rows;
    }

    @Test
    void stitchesFinestFirstAndIgnoresMax() {
        RrdFile rrd = synthetic("GAUGE", 5, 0, L, new double[] {1.1, 1.2, 1.3, 1.4}, new double[] {2.1, 2.2, 2.3});
        Series s = SampleGenerator.generate(rrd, Pass.ALL).get(0);
        assertThat(s.counter()).isFalse();
        assertThat(points(s)).containsExactly(
                Map.entry(L - 7200, 2.1), Map.entry(L - 3600, 2.2),
                Map.entry(L - 900, 1.1), Map.entry(L - 600, 1.2), Map.entry(L - 300, 1.3), Map.entry(L, 1.4));
    }

    @Test
    void reconstructsCounterBackwardsFromLastDs() {
        RrdFile rrd = synthetic("COUNTER", 10000, 50, L + 100,
                new double[] {1, 2, NaN, 4}, new double[] {0.5, 0.25, 7});
        Series s = SampleGenerator.generate(rrd, Pass.ALL).get(0);
        assertThat(s.counter()).isTrue();
        assertThat(points(s)).containsExactly(
                Map.entry(L - 10800, 5150.0), Map.entry(L - 7200, 6950.0), Map.entry(L - 3600, 7850.0),
                Map.entry(L - 1200, 7850.0), Map.entry(L - 900, 8150.0), Map.entry(L - 600, 8750.0),
                Map.entry(L - 300, 8750.0), Map.entry(L, 9950.0), Map.entry(L + 100, 10000.0));
    }

    @Test
    void unknownLastDsAnchorsAtZeroWithoutNaN() {
        RrdFile rrd = synthetic("DERIVE", NaN, NaN, L, new double[] {1, 1, 1, 1}, new double[] {NaN, NaN, NaN});
        Map<Long, Double> p = points(SampleGenerator.generate(rrd, Pass.ALL).get(0));
        assertThat(p).doesNotContainKey(L + 100).containsEntry(L, 0.0).containsEntry(L - 1200, -1200.0);
        assertThat(p.values()).noneMatch(v -> Double.isNaN(v));
    }

    @Test
    void skipsAbsoluteDataSources() {
        RrdFile rrd = synthetic("ABSOLUTE", 1, 0, L, new double[] {1, 1, 1, 1}, new double[] {1, 1, 1});
        assertThat(SampleGenerator.generate(rrd, Pass.ALL)).isEmpty();
        assertThat(SampleGenerator.supported(rrd.dataSources().get(0))).isFalse();
    }

    static RrdFile fixture(String arch, String name) throws Exception {
        try (InputStream in = Files.newInputStream(Fixtures.path(arch, name + ".xml"))) {
            return DumpXmlParser.parse(in);
        }
    }

    @ParameterizedTest
    @FieldSource("org.no42.rrdlift.rrd.Fixtures#ARCHES")
    void counterDeltasMatchRawUpdatesAndAnchorIsExact(String arch) throws Exception {
        RrdFile rrd = fixture(arch, "grp");
        List<Series> series = SampleGenerator.generate(rrd, Pass.ALL);
        List<String> raw = Files.readAllLines(Fixtures.path(arch, "grp.raw"));
        // fields: t, ifInOctets(32-bit), ifOutOctets, ifHCInOctets, ifHCOutOctets, ifInErrors, ifInDiscards, ...
        for (int ds : new int[] {0, 2, 4}) {
            Map<Long, Double> p = points(series.get(ds));
            assertThat(p.get(rrd.lastUpdate())).isEqualTo(rrd.dataSources().get(ds).lastValue());
            int compared = 0;
            for (int i = 1; i < raw.size(); i++) {
                String[] prev = raw.get(i - 1).split(":");
                String[] cur = raw.get(i).split(":");
                long t0 = Long.parseLong(prev[0]);
                long t1 = Long.parseLong(cur[0]);
                if (t1 - t0 != 300 || !p.containsKey(t0) || !p.containsKey(t1)) {
                    continue;
                }
                BigInteger d = new BigInteger(cur[ds + 1]).subtract(new BigInteger(prev[ds + 1]));
                if (ds == 0 && d.signum() < 0) {
                    d = d.add(BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE)); // rrdtool wraps 32-bit counters with 2^32-1 (verified on 1.7.2 and 1.8.0)
                }
                double expected = d.doubleValue();
                double actual = p.get(t1) - p.get(t0);
                double tol = Math.max(1e-9 * Math.abs(expected), 2 * Math.ulp(p.get(t1)));
                assertThat(Math.abs(actual - expected)).as("ds %d at %d", ds, t1).isLessThanOrEqualTo(tol);
                compared++;
            }
            assertThat(compared).as("compared intervals for ds %d", ds).isGreaterThan(1000);
        }
    }

    @ParameterizedTest
    @FieldSource("org.no42.rrdlift.rrd.Fixtures#ARCHES")
    void passesSplitSamplesAtSevenDays(String arch) throws Exception {
        RrdFile rrd = fixture(arch, "grp");
        long cut = (rrd.lastUpdate() - Pass.RECENT_SECONDS) * 1000;
        for (int ds = 0; ds < rrd.dataSources().size(); ds++) {
            Series all = SampleGenerator.generate(rrd, Pass.ALL).get(ds);
            Series recent = SampleGenerator.generate(rrd, Pass.RECENT).get(ds);
            Series older = SampleGenerator.generate(rrd, Pass.OLDER).get(ds);
            assertThat(recent.size() + older.size()).isEqualTo(all.size());
            assertThat(java.util.Arrays.stream(recent.timesMs())).allMatch(t -> t > cut);
            assertThat(java.util.Arrays.stream(older.timesMs())).allMatch(t -> t <= cut);
        }
    }
}
