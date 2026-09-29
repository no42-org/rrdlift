/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

class MainTest {

    @Test
    void versionPrintsProjectVersion() {
        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true));
        try {
            assertThat(Main.run("--version")).isZero();
        } finally {
            System.setOut(original);
        }
        assertThat(out.toString()).startsWith("rrdlift 0.1.0-SNAPSHOT");
    }
}
