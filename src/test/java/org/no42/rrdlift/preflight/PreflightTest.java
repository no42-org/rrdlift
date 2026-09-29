/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.preflight;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.no42.rrdlift.prom.FakeBackend;
import org.no42.rrdlift.prom.PromClient;

class PreflightTest {

    static final long NOW = 1_800_000_000L;
    static final long OLDEST = NOW - 400 * 86400L;

    FakeBackend backend;
    PromClient client;

    @BeforeEach
    void start() throws Exception {
        backend = FakeBackend.start();
        client = new PromClient(backend.writeUrl(), backend.readUrl(), null);
    }

    @AfterEach
    void stop() {
        backend.close();
    }

    @Test
    void passesWhenOldSamplesAreAcceptedAndReadable() {
        Preflight.Result r = Preflight.run(client, OLDEST, NOW, 1, 0);
        assertThat(r.failures()).isEmpty();
        assertThat(r.ok()).isTrue();
        assertThat(backend.data().keySet()).allMatch(l -> l.get("__name__").equals("rrdlift_probe") && l.containsKey("run"));
    }

    @Test
    void namesOutOfOrderWindowWhenOldSampleIsRejected() {
        backend.rejectOlderThanMs((NOW - 86400) * 1000);
        Preflight.Result r = Preflight.run(client, OLDEST, NOW, 1, 0);
        assertThat(r.ok()).isFalse();
        assertThat(r.failures()).anyMatch(f -> f.contains("out_of_order_time_window"));
    }

    @Test
    void namesRetentionWhenOldSampleIsSilentlyDropped() {
        backend.dropOlderThanMs((NOW - 86400) * 1000);
        Preflight.Result r = Preflight.run(client, OLDEST, NOW, 1, 0);
        assertThat(r.failures()).anyMatch(f -> f.contains("retention"));
    }

    @Test
    void namesReceiverWhenWritesFail() {
        backend.failNextWrites(1, 404);
        Preflight.Result r = Preflight.run(client, OLDEST, NOW, 1, 0);
        assertThat(r.failures()).singleElement().asString().contains("remote-write receiver");
    }
}
