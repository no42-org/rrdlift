/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.samples;

/** Samples of one data source in ascending time. Counters hold synthetic raw counter values. */
public record Series(String dsName, boolean counter, long[] timesMs, double[] values) {

    public int size() {
        return timesMs.length;
    }
}
