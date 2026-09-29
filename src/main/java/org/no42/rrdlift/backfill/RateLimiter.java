/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.backfill;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Samples-per-second limit shared by all writer threads. No burst credit accumulates while idle. */
public final class RateLimiter {

    public interface Sleeper {
        void sleepNanos(long nanos) throws InterruptedException;
    }

    private final double perSecond;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private long nextFree;

    public RateLimiter(double perSecond) {
        this(perSecond, System::nanoTime, TimeUnit.NANOSECONDS::sleep);
    }

    public RateLimiter(double perSecond, LongSupplier nanoClock, Sleeper sleeper) {
        this.perSecond = perSecond;
        this.clock = nanoClock;
        this.sleeper = sleeper;
        this.nextFree = nanoClock.getAsLong();
    }

    public void acquire(int permits) throws InterruptedException {
        if (perSecond <= 0) {
            return;
        }
        long start;
        synchronized (this) {
            long now = clock.getAsLong();
            start = Math.max(now, nextFree);
            nextFree = start + (long) (permits * 1e9 / perSecond);
        }
        long wait = start - clock.getAsLong();
        if (wait > 0) {
            sleeper.sleepNanos(wait);
        }
    }
}
