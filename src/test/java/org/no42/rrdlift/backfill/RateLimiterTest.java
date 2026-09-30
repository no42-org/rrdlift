/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    @Test
    void spacesPermitsAtTheConfiguredRate() throws Exception {
        AtomicLong clock = new AtomicLong();
        List<Long> sleeps = new ArrayList<>();
        RateLimiter limiter = new RateLimiter(100, clock::get, n -> {
            sleeps.add(n);
            clock.addAndGet(n);
        });
        limiter.acquire(100); // free
        limiter.acquire(100); // waits 1 s
        limiter.acquire(50);  // waits 1 s for the previous batch's budget
        assertThat(sleeps).containsExactly(1_000_000_000L, 1_000_000_000L);
    }

    @Test
    void doesNotAccumulateBurstCreditWhileIdle() throws Exception {
        AtomicLong clock = new AtomicLong();
        List<Long> sleeps = new ArrayList<>();
        RateLimiter limiter = new RateLimiter(100, clock::get, n -> {
            sleeps.add(n);
            clock.addAndGet(n);
        });
        limiter.acquire(100);
        clock.addAndGet(10_000_000_000L); // idle 10 s
        limiter.acquire(100);
        limiter.acquire(100);
        assertThat(sleeps).containsExactly(1_000_000_000L);
    }

    @Test
    void unlimitedNeverSleeps() throws Exception {
        List<Long> sleeps = new ArrayList<>();
        RateLimiter limiter = new RateLimiter(0, () -> 0L, sleeps::add);
        limiter.acquire(1_000_000);
        assertThat(sleeps).isEmpty();
    }
}
