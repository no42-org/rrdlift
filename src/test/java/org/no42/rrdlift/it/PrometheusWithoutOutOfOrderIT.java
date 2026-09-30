/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.no42.rrdlift.Main;
import org.no42.rrdlift.repo.TestRepos;
import org.testcontainers.containers.GenericContainer;

class PrometheusWithoutOutOfOrderIT {

    @Test
    void preflightRefusesWithoutOutOfOrderWindow(@TempDir Path tmp) throws Exception {
        try (GenericContainer<?> prometheus = PrometheusIT.prometheus("global: {}\n")) {
            prometheus.start();
            URI base = PrometheusIT.base(prometheus);
            Path repo = TestRepos.create(tmp.resolve("rrd"));
            int exit = Main.run("preflight", "--rrd-dir", repo.toString(),
                    "--write-url", base + "/api/v1/write", "--read-url", base.toString());
            assertThat(exit).isEqualTo(1);
        }
    }
}
