/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.no42.rrdlift.Main;
import org.no42.rrdlift.prom.FakeBackend;
import org.no42.rrdlift.repo.TestRepos;
import org.no42.rrdlift.rrd.Fixtures;

class PreflightCommandTest {

    @Test
    void scansRepositoryForOldestSampleAndPasses(@TempDir Path tmp) throws Exception {
        Path repo = TestRepos.create(tmp);
        try (FakeBackend backend = FakeBackend.start()) {
            int exit = Main.run("preflight", "--rrd-dir", repo.toString(),
                    "--write-url", backend.writeUrl().toString(), "--read-url", backend.readUrl().toString());
            assertThat(exit).isZero();
            long oldestProbeMs = backend.data().values().stream()
                    .mapToLong(m -> m.firstKey()).min().orElseThrow();
            assertThat(oldestProbeMs).isLessThan(System.currentTimeMillis() - 300L * 86400 * 1000);
        }
    }

    @Test
    void failsWhenBackendRejectsOldSamples(@TempDir Path tmp) throws Exception {
        Path repo = TestRepos.create(tmp);
        try (FakeBackend backend = FakeBackend.start()) {
            backend.rejectOlderThanMs(System.currentTimeMillis() - 3600_000L);
            int exit = Main.run("preflight", "--rrd-dir", repo.toString(),
                    "--write-url", backend.writeUrl().toString(), "--read-url", backend.readUrl().toString());
            assertThat(exit).isEqualTo(1);
        }
    }

    @Test
    void skipsUnreadableFileAndStillPasses(@TempDir Path tmp) throws Exception {
        Path repo = TestRepos.create(tmp);
        Path badDir = Files.createDirectories(repo.resolve("snmp/bad/store"));
        Files.writeString(badDir.resolve("ds.properties"), "x=bad\n");
        byte[] badRrd = Files.readAllBytes(Fixtures.path("aarch64", "icmp.rrd"));
        ByteBuffer buf = ByteBuffer.wrap(badRrd);
        buf.putLong(24, -1L);
        Files.write(badDir.resolve("bad.rrd"), badRrd);
        try (FakeBackend backend = FakeBackend.start()) {
            int exit = Main.run("preflight", "--rrd-dir", repo.toString(),
                    "--write-url", backend.writeUrl().toString(), "--read-url", backend.readUrl().toString());
            assertThat(exit).isZero();
        }
    }

    @Test
    void emptyRepositoryExitsOne(@TempDir Path tmp) throws Exception {
        try (FakeBackend backend = FakeBackend.start()) {
            int exit = Main.run("preflight", "--rrd-dir", tmp.toString(),
                    "--write-url", backend.writeUrl().toString(), "--read-url", backend.readUrl().toString());
            assertThat(exit).isEqualTo(1);
        }
    }

    @Test
    void missingUrlsExitWith64(@TempDir Path tmp) {
        assertThat(Main.run("preflight", "--rrd-dir", tmp.toString(), "--oldest-epoch", "1",
                "--read-url", "http://127.0.0.1:1")).isEqualTo(64);
        assertThat(Main.run("preflight", "--rrd-dir", tmp.toString(), "--oldest-epoch", "1",
                "--write-url", "http://127.0.0.1:1/api/v1/write")).isEqualTo(64);
    }
}
