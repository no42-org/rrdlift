/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.it;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

class VictoriaMetricsIT {

    static GenericContainer<?> vm;

    @BeforeAll
    static void start() {
        vm = new GenericContainer<>(DockerImageName.parse("victoriametrics/victoria-metrics:v1.102.0"))
                .withCommand("-retentionPeriod=100y", "-search.disableCache")
                .withExposedPorts(8428)
                .waitingFor(Wait.forHttp("/health").forStatusCode(200));
        vm.start();
    }

    @AfterAll
    static void stop() {
        vm.stop();
    }

    @Test
    void migratesHistoryIntoVictoriaMetrics(@TempDir Path tmp) throws Exception {
        URI base = URI.create("http://" + vm.getHost() + ":" + vm.getMappedPort(8428));
        Runnable flush = () -> {
            try {
                HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + "/internal/force_flush")).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Migration.run(tmp, URI.create(base + "/api/v1/write"), base, flush);
    }
}
