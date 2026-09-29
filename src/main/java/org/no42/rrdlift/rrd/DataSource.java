/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.rrd;

/**
 * One data source: its definition and the PDP state at the last update.
 * {@code lastValue} is the raw last reading ({@code last_ds}), NaN when unknown.
 * {@code pdpValue} is the amount accumulated since the last step boundary.
 */
public record DataSource(String name, String type, long heartbeat, double min, double max,
                         double lastValue, double pdpValue, long unknownSec) {

    public boolean isCounter() {
        return "COUNTER".equals(type) || "DERIVE".equals(type);
    }
}
