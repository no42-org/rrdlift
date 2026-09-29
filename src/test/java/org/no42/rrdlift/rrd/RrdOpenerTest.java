/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.rrd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RrdOpenerTest {

    @Test
    void detectsFormatByContentNotExtension(@TempDir Path tmp) throws Exception {
        Path jrb = JrobinReaderTest.create(tmp);
        Path jrbNamedRrd = tmp.resolve("renamed.rrd");
        Files.copy(jrb, jrbNamedRrd);
        Path rrdNamedJrb = tmp.resolve("renamed.jrb");
        Files.copy(Fixtures.path("aarch64", "icmp.rrd"), rrdNamedJrb);

        RrdOpener opener = new RrdOpener(RrdOpener.Mode.NATIVE, null);
        assertThat(opener.open(jrbNamedRrd).version()).isEqualTo("JRobin, version 0.1");
        assertThat(opener.open(rrdNamedJrb).version()).isEqualTo("0003");
        assertThat(RrdOpener.isRrdtoolFile(rrdNamedJrb)).isTrue();
        assertThat(RrdOpener.isRrdtoolFile(jrbNamedRrd)).isFalse();
    }

    @Test
    void unsupportedLayoutWithoutBinaryNamesTheFallback(@TempDir Path tmp) throws Exception {
        byte[] bytes = Files.readAllBytes(Fixtures.path("aarch64", "icmp.rrd"));
        bytes[7] = '1';
        Path old = tmp.resolve("old.rrd");
        Files.write(old, bytes);
        RrdOpener opener = new RrdOpener(RrdOpener.Mode.NATIVE, null);
        assertThatThrownBy(() -> opener.open(old))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("--rrdtool");
    }

    @Test
    void rrdtoolModeWithoutBinaryFails() {
        RrdOpener opener = new RrdOpener(RrdOpener.Mode.RRDTOOL, null);
        assertThatThrownBy(() -> opener.open(Fixtures.path("aarch64", "icmp.rrd")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("--rrdtool");
    }
}
