/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.prom;

import java.util.Map;

/** One series and its samples to write. Times are epoch milliseconds. */
public record TimeSeries(Map<String, String> labels, long[] timesMs, double[] values) {}
