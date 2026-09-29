/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.prom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PromClientTest {

    FakeBackend backend;
    PromClient client;

    @BeforeEach
    void start() throws Exception {
        backend = FakeBackend.start();
        client = new PromClient(backend.writeUrl(), backend.readUrl(), "tenant-a");
    }

    @AfterEach
    void stop() {
        backend.close();
    }

    static final Map<String, String> LABELS = Map.of("__name__", "icmp", "resourceId", "response/10.0.0.1/icmp");

    @Test
    void writesAndReadsBack() throws Exception {
        Map<String, String> decoy = Map.of("__name__", "icmp", "resourceId", "response/10x0x0x1/icmp");
        client.write(List.of(new TimeSeries(LABELS, new long[] {1_000_000, 1_300_000}, new double[] {1.5, 2.5}),
                new TimeSeries(decoy, new long[] {1_000_000}, new double[] {9})));
        assertThat(backend.data().get(LABELS)).containsEntry(1_000_000L, 1.5).containsEntry(1_300_000L, 2.5);
        assertThat(backend.requests()).anyMatch(r -> r.contains("X-Scope-OrgID=tenant-a"));

        List<PromClient.QueryResult> range = client.query(Selectors.exact(LABELS) + "[400s]", 1_300);
        assertThat(range).hasSize(1);
        assertThat(range.get(0).timesMs()).containsExactly(1_000_000, 1_300_000);
        assertThat(range.get(0).values()).containsExactly(1.5, 2.5);

        List<PromClient.QueryResult> instant = client.query(Selectors.exact(LABELS), 1_000);
        assertThat(instant.get(0).values()).containsExactly(1.5);
        assertThat(instant.get(0).timesMs()).containsExactly(1_000_000);
        assertThat(client.query(Selectors.exact(LABELS), 1_100).get(0).timesMs()).containsExactly(1_100_000);

        assertThat(client.series(Selectors.resourcePrefix("response/10.0.0.1"), 0, 2_000)).containsExactly(LABELS);
        assertThat(client.series(Selectors.resourcePrefix("response/10.0.0.10"), 0, 2_000)).isEmpty();
    }

    @Test
    void seriesHonoursTimeWindow() throws Exception {
        client.write(List.of(new TimeSeries(LABELS, new long[] {1_000_000}, new double[] {1})));
        assertThat(client.series(Selectors.exact(LABELS), 1_000, 1_000)).containsExactly(LABELS);
        assertThat(client.series(Selectors.exact(LABELS), 0, 999)).isEmpty();
        assertThat(client.series(Selectors.exact(LABELS), 1_001, 2_000)).isEmpty();
    }

    @Test
    void partialIngestStoresInBoundsSamples() {
        backend.rejectOlderThanMs(5000);
        List<TimeSeries> mixed = List.of(new TimeSeries(LABELS, new long[] {1000, 6000}, new double[] {1, 2}));
        assertThatThrownBy(() -> client.write(mixed)).isInstanceOfSatisfying(WriteException.class,
                e -> assertThat(e.status()).isEqualTo(400));
        assertThat(backend.data().get(LABELS)).containsOnlyKeys(6000L);
    }

    @Test
    void unsupportedMatchersAreRejected() {
        assertThatThrownBy(() -> client.series("{resourceId!=\"x\"}", 0, 2_000))
                .hasMessageContaining("unsupported matcher");
        assertThatThrownBy(() -> client.query("{resourceId!~\"x\"}", 1_000))
                .hasMessageContaining("unsupported matcher");
    }

    @Test
    void classifiesWriteErrors() {
        List<TimeSeries> one = List.of(new TimeSeries(LABELS, new long[] {1000}, new double[] {1}));
        backend.failNextWrites(1, 503);
        assertThatThrownBy(() -> client.write(one)).isInstanceOfSatisfying(WriteException.class,
                e -> assertThat(e.retryable()).isTrue());
        backend.failNextWrites(1, 429);
        assertThatThrownBy(() -> client.write(one)).isInstanceOfSatisfying(WriteException.class,
                e -> assertThat(e.retryable()).isTrue());
        backend.rejectOlderThanMs(5000);
        assertThatThrownBy(() -> client.write(one)).isInstanceOfSatisfying(WriteException.class, e -> {
            assertThat(e.retryable()).isFalse();
            assertThat(e.status()).isEqualTo(400);
            assertThat(e.body()).contains("out of bounds");
        });
    }
}
