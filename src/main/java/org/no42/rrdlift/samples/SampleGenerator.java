/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Derived from OpenNMS NewtsConverter, Copyright The OpenNMS Group, Inc.
 */
package org.no42.rrdlift.samples;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.no42.rrdlift.rrd.Archive;
import org.no42.rrdlift.rrd.DataSource;
import org.no42.rrdlift.rrd.RrdFile;

/** Turns RRD archives into gauge samples or synthetic raw counter samples. */
public final class SampleGenerator {

    private SampleGenerator() {}

    /** One consolidated interval (end - span, end]. */
    private record Interval(long end, long span, double[] values) {}

    public static boolean supported(DataSource ds) {
        return switch (ds.type()) {
            case "GAUGE", "COUNTER", "DERIVE" -> true;
            default -> false;
        };
    }

    public static List<Series> generate(RrdFile rrd, Pass pass) {
        List<Interval> intervals = stitch(rrd);
        List<Series> out = new ArrayList<>();
        for (int i = 0; i < rrd.dataSources().size(); i++) {
            DataSource ds = rrd.dataSources().get(i);
            if (!supported(ds)) {
                continue;
            }
            Map<Long, Double> points = ds.isCounter()
                    ? counter(rrd, ds, i, intervals)
                    : gauge(i, intervals);
            out.add(toSeries(ds, points, pass, rrd.lastUpdate()));
        }
        return out;
    }

    static List<Archive> selectArchives(RrdFile rrd) {
        Optional<Archive> finest = rrd.archives().stream()
                .filter(a -> a.pdpPerRow() == 1)
                .max(Comparator.comparingInt(a -> a.rows().length));
        List<Archive> selected = new ArrayList<>();
        finest.ifPresent(selected::add);
        rrd.archives().stream()
                .filter(a -> a.cf().equals("AVERAGE"))
                .sorted(Comparator.comparingLong(Archive::pdpPerRow))
                .filter(a -> selected.stream().noneMatch(s -> s.pdpPerRow() == a.pdpPerRow()))
                .forEach(selected::add);
        selected.sort(Comparator.comparingLong(Archive::pdpPerRow));
        return selected;
    }

    /** Earliest row end time among the archives the generator uses; Long.MAX_VALUE if there are none. */
    public static long oldestRowTime(RrdFile rrd) {
        long oldest = Long.MAX_VALUE;
        for (Archive a : selectArchives(rrd)) {
            if (a.rows().length > 0) {
                oldest = Math.min(oldest, a.rowTime(0, rrd.step(), rrd.lastUpdate()));
            }
        }
        return oldest;
    }

    /** Non-overlapping intervals, newest first. */
    private static List<Interval> stitch(RrdFile rrd) {
        List<Interval> out = new ArrayList<>();
        long coverageStart = Long.MAX_VALUE;
        for (Archive a : selectArchives(rrd)) {
            long span = rrd.step() * a.pdpPerRow();
            for (int j = a.rows().length - 1; j >= 0; j--) {
                long t = a.rowTime(j, rrd.step(), rrd.lastUpdate());
                if (t <= coverageStart) {
                    out.add(new Interval(t, span, a.rows()[j]));
                }
            }
            if (a.rows().length > 0) {
                coverageStart = Math.min(coverageStart, a.rowTime(0, rrd.step(), rrd.lastUpdate()) - span);
            }
        }
        return out;
    }

    private static Map<Long, Double> gauge(int ds, List<Interval> intervals) {
        Map<Long, Double> points = new TreeMap<>();
        for (Interval iv : intervals) {
            double v = iv.values()[ds];
            if (!Double.isNaN(v)) {
                points.put(iv.end(), v);
            }
        }
        return points;
    }

    private static Map<Long, Double> counter(RrdFile rrd, DataSource d, int ds, List<Interval> intervals) {
        Map<Long, Double> points = new TreeMap<>();
        if (intervals.isEmpty()) {
            return points;
        }
        double c;
        if (Double.isNaN(d.lastValue())) {
            c = 0;
        } else {
            points.put(rrd.lastUpdate(), d.lastValue());
            c = d.lastValue() - (Double.isNaN(d.pdpValue()) ? 0 : d.pdpValue());
        }
        long cursor = intervals.get(0).end();
        for (Interval iv : intervals) {
            if (iv.end() < cursor) {
                cursor = iv.end(); // gap between archives: no increase
            }
            double rate = iv.values()[ds];
            if (!Double.isNaN(rate)) {
                points.put(iv.end(), c);
                c -= rate * iv.span();
                points.put(iv.end() - iv.span(), c);
            }
            cursor = iv.end() - iv.span();
        }
        return points;
    }

    private static Series toSeries(DataSource ds, Map<Long, Double> points, Pass pass, long lastUpdate) {
        long[] times = new long[points.size()];
        double[] values = new double[points.size()];
        int n = 0;
        for (Map.Entry<Long, Double> e : points.entrySet()) {
            if (pass.includes(e.getKey(), lastUpdate)) {
                times[n] = e.getKey() * 1000;
                values[n] = e.getValue();
                n++;
            }
        }
        return new Series(ds.name(), ds.isCounter(), java.util.Arrays.copyOf(times, n), java.util.Arrays.copyOf(values, n));
    }
}
