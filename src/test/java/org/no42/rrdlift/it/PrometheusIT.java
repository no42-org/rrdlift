/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.it;

import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

class PrometheusIT {

    static final String CONFIG = """
            storage:
              tsdb:
                out_of_order_time_window: 3650d
            """;

    static GenericContainer<?> prometheus;

    @BeforeAll
    static void start() {
        prometheus = prometheus(CONFIG);
        prometheus.start();
    }

    @AfterAll
    static void stop() {
        prometheus.stop();
    }

    static GenericContainer<?> prometheus(String config) {
        return new GenericContainer<>(DockerImageName.parse("prom/prometheus:v3.0.0"))
                .withCopyToContainer(Transferable.of(config), "/etc/prometheus/prometheus.yml")
                .withCommand("--config.file=/etc/prometheus/prometheus.yml", "--storage.tsdb.path=/prometheus",
                        "--storage.tsdb.retention.time=20y", "--web.enable-remote-write-receiver")
                .withExposedPorts(9090)
                .waitingFor(Wait.forHttp("/-/ready").forStatusCode(200));
    }

    static URI base(GenericContainer<?> c) {
        return URI.create("http://" + c.getHost() + ":" + c.getMappedPort(9090));
    }

    @Test
    void migratesHistoryIntoPrometheus(@TempDir Path tmp) throws Exception {
        URI base = base(prometheus);
        Migration.run(tmp, URI.create(base + "/api/v1/write"), base, () -> { }, 0);
    }
}
