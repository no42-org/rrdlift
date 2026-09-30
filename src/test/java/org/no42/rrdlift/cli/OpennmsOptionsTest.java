/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.no42.rrdlift.opennms.FakeOpennms;
import picocli.CommandLine;

/** The RRDLIFT_OPENNMS_PASSWORD path is not tested: the environment cannot be changed in-process. */
class OpennmsOptionsTest {

    @TempDir
    Path tmp;

    private static OpennmsOptions parse(String... args) {
        OpennmsOptions o = new OpennmsOptions();
        new CommandLine(o).parseArgs(args);
        return o;
    }

    @Test
    void noRestGivesNull() throws Exception {
        assertThat(parse("--no-rest", "--opennms-url", "http://x/opennms").client()).isNull();
    }

    @Test
    void noUrlGivesNull() throws Exception {
        assertThat(parse().client()).isNull();
    }

    @Test
    void passwordFileWithTrailingNewlineWorks() throws Exception {
        Path pw = tmp.resolve("pw");
        Files.writeString(pw, "admin\n");
        try (FakeOpennms onms = FakeOpennms.start()) {
            assertThat(parse("--opennms-url", onms.url().toString(), "--opennms-password-file", pw.toString())
                    .client()).isNotNull();
        }
    }

    @Test
    void blankPasswordFileFails() throws Exception {
        Path pw = tmp.resolve("pw");
        Files.writeString(pw, " \n");
        OpennmsOptions o = parse("--opennms-url", "http://127.0.0.1:1/opennms", "--opennms-password-file", pw.toString());
        assertThatThrownBy(o::client).isInstanceOf(IOException.class).hasMessageContaining("empty OpenNMS password");
    }

    @Test
    void wrongUrlFailsAtStartup() throws Exception {
        Path pw = tmp.resolve("pw");
        Files.writeString(pw, "admin\n");
        try (FakeOpennms onms = FakeOpennms.start()) {
            OpennmsOptions o = parse("--opennms-url", onms.url() + "/wrong", "--opennms-password-file", pw.toString());
            assertThatThrownBy(o::client).isInstanceOf(IOException.class)
                    .hasMessageContaining("is not an OpenNMS REST API");
        }
    }
}
