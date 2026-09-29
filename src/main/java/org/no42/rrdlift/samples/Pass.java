/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.samples;

/** Backfill passes: the last 7 days first, then everything older. */
public enum Pass {
    RECENT, OLDER, ALL;

    public static final long RECENT_SECONDS = 7 * 86400L;

    public boolean includes(long timeSec, long lastUpdateSec) {
        long cut = lastUpdateSec - RECENT_SECONDS;
        return switch (this) {
            case RECENT -> timeSec > cut;
            case OLDER -> timeSec <= cut;
            case ALL -> true;
        };
    }
}
