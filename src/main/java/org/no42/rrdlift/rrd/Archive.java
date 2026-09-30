/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.rrd;

/** One RRA. {@code rows[row][ds]}, oldest row first. Row values are per-second rates for counters. */
public record Archive(String cf, long pdpPerRow, double xff, double[][] rows) {

    /** Timestamp in seconds of the newest row. */
    public long endTime(long step, long lastUpdate) {
        long span = step * pdpPerRow;
        return lastUpdate - lastUpdate % span;
    }

    /** Timestamp in seconds of the end of the interval that row {@code row} consolidates. */
    public long rowTime(int row, long step, long lastUpdate) {
        return endTime(step, lastUpdate) - (long) (rows.length - 1 - row) * step * pdpPerRow;
    }
}
