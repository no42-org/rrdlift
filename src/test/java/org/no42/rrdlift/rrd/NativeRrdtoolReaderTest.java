/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.rrd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class NativeRrdtoolReaderTest {

    static List<Arguments> fixtures() {
        List<Arguments> args = new ArrayList<>();
        for (String arch : Fixtures.ARCHES) {
            for (String file : Fixtures.FILES) {
                args.add(Arguments.of(arch, file));
            }
        }
        return args;
    }

    /** `rrdtool dump` prints %0.10e, so values agree to about 1e-10 relative. */
    static boolean close(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b)) {
            return Double.isNaN(a) && Double.isNaN(b);
        }
        return a == b || Math.abs(a - b) <= 1e-9 * Math.max(Math.abs(a), Math.abs(b));
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void matchesRrdtoolDump(String arch, String file) throws Exception {
        RrdFile expected;
        try (InputStream in = Files.newInputStream(Fixtures.path(arch, file + ".xml"))) {
            expected = DumpXmlParser.parse(in);
        }
        RrdFile actual = NativeRrdtoolReader.read(Fixtures.path(arch, file + ".rrd"));

        assertThat(actual.version()).isEqualTo(expected.version());
        assertThat(actual.step()).isEqualTo(expected.step());
        assertThat(actual.lastUpdate()).isEqualTo(expected.lastUpdate());
        assertThat(actual.dataSources()).hasSameSizeAs(expected.dataSources());
        for (int i = 0; i < expected.dataSources().size(); i++) {
            DataSource e = expected.dataSources().get(i);
            DataSource a = actual.dataSources().get(i);
            assertThat(a.name()).isEqualTo(e.name());
            assertThat(a.type()).isEqualTo(e.type());
            assertThat(a.heartbeat()).isEqualTo(e.heartbeat());
            assertThat(close(a.min(), e.min())).as("min of %s", e.name()).isTrue();
            assertThat(close(a.max(), e.max())).as("max of %s", e.name()).isTrue();
            assertThat(close(a.lastValue(), e.lastValue())).as("last_ds of %s", e.name()).isTrue();
            assertThat(close(a.pdpValue(), e.pdpValue())).as("pdp value of %s", e.name()).isTrue();
            assertThat(a.unknownSec()).isEqualTo(e.unknownSec());
        }
        assertThat(actual.archives()).hasSameSizeAs(expected.archives());
        for (int k = 0; k < expected.archives().size(); k++) {
            Archive e = expected.archives().get(k);
            Archive a = actual.archives().get(k);
            assertThat(a.cf()).isEqualTo(e.cf());
            assertThat(a.pdpPerRow()).isEqualTo(e.pdpPerRow());
            assertThat(close(a.xff(), e.xff())).isTrue();
            assertThat(a.rows().length).isEqualTo(e.rows().length);
            for (int j = 0; j < e.rows().length; j++) {
                for (int i = 0; i < e.rows()[j].length; i++) {
                    assertThat(close(a.rows()[j][i], e.rows()[j][i]))
                            .as("%s rra[%d] row %d ds %d", file, k, j, i).isTrue();
                }
            }
        }
    }

    @Test
    void rejectsTruncatedFile(@TempDir Path tmp) throws IOException {
        byte[] bytes = Files.readAllBytes(Fixtures.path("aarch64", "icmp.rrd"));
        Path cut = tmp.resolve("cut.rrd");
        Files.write(cut, java.util.Arrays.copyOf(bytes, bytes.length - 8));
        assertThatThrownBy(() -> NativeRrdtoolReader.read(cut))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(UnsupportedLayoutException.class);
    }

    @Test
    void rejectsOtherVersion(@TempDir Path tmp) throws IOException {
        byte[] bytes = Files.readAllBytes(Fixtures.path("aarch64", "icmp.rrd"));
        bytes[7] = '1'; // "0003" -> "0001"
        Path old = tmp.resolve("old.rrd");
        Files.write(old, bytes);
        assertThatThrownBy(() -> NativeRrdtoolReader.read(old))
                .isInstanceOf(UnsupportedLayoutException.class)
                .hasMessageContaining("0001");
    }

    @Test
    void rejectsBigEndian(@TempDir Path tmp) throws IOException {
        byte[] bytes = Files.readAllBytes(Fixtures.path("aarch64", "icmp.rrd"));
        for (int i = 0; i < 4; i++) { // reverse the float cookie at offset 16
            byte b = bytes[16 + i];
            bytes[16 + i] = bytes[23 - i];
            bytes[23 - i] = b;
        }
        Path be = tmp.resolve("be.rrd");
        Files.write(be, bytes);
        assertThatThrownBy(() -> NativeRrdtoolReader.read(be))
                .isInstanceOf(UnsupportedLayoutException.class)
                .hasMessageContaining("BIG_ENDIAN");
    }

    static Path patchLong(Path tmp, String fixture, int offset, long value) throws IOException {
        byte[] bytes = Files.readAllBytes(Fixtures.path("aarch64", fixture));
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(offset, value);
        Path out = tmp.resolve("patched-" + offset + ".rrd");
        Files.write(out, bytes);
        return out;
    }

    @Test
    void rejectsHugeDataSourceCountWithoutAllocating(@TempDir Path tmp) throws IOException {
        Path file = patchLong(tmp, "icmp.rrd", 24, 1L << 40);
        assertThatThrownBy(() -> NativeRrdtoolReader.read(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("header counts do not fit the file size");
    }

    @Test
    void rejectsNegativeDataSourceCount(@TempDir Path tmp) throws IOException {
        Path file = patchLong(tmp, "icmp.rrd", 24, -1L);
        assertThatThrownBy(() -> NativeRrdtoolReader.read(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("header counts do not fit the file size");
    }

    @Test
    void rejectsHugeRowCountWithoutAllocating(@TempDir Path tmp) throws IOException {
        byte[] bytes = Files.readAllBytes(Fixtures.path("aarch64", "grp.rrd"));
        long ds = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(24);
        int firstRowCnt = (int) (128 + ds * 120 + 24); // header, ds defs, cf_nam[20] + 4 padding
        for (long rows : new long[] {Integer.MAX_VALUE, 1L << 40, -5}) {
            Path file = patchLong(tmp, "grp.rrd", firstRowCnt, rows);
            assertThatThrownBy(() -> NativeRrdtoolReader.read(file)).as("row count %d", rows)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("header counts do not fit the file size");
        }
    }
}
