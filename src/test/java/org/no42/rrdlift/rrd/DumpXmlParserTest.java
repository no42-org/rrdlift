/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.rrd;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

class DumpXmlParserTest {

    static RrdFile parse(String arch, String name) throws Exception {
        try (InputStream in = Files.newInputStream(Fixtures.path(arch, name + ".xml"))) {
            return DumpXmlParser.parse(in);
        }
    }

    @ParameterizedTest
    @FieldSource("org.no42.rrdlift.rrd.Fixtures#ARCHES")
    void parsesStoreByGroupFile(String arch) throws Exception {
        RrdFile rrd = parse(arch, "grp");
        assertThat(rrd.version()).isEqualTo("0003");
        assertThat(rrd.step()).isEqualTo(300);
        assertThat(rrd.lastUpdate() % 300).isZero();
        assertThat(rrd.dataSources()).extracting(DataSource::name).containsExactly(
                "ifInOctets", "ifOutOctets", "ifHCInOctets", "ifHCOutOctets",
                "ifInErrors", "ifInDiscards", "ifSpeed", "cpuPercent");
        assertThat(rrd.dataSources()).extracting(DataSource::type).containsExactly(
                "COUNTER", "COUNTER", "COUNTER", "COUNTER", "COUNTER", "DERIVE", "GAUGE", "GAUGE");
        assertThat(rrd.dataSources().get(5).isCounter()).isTrue();
        assertThat(rrd.dataSources().get(6).isCounter()).isFalse();
        assertThat(rrd.dataSources().get(0).heartbeat()).isEqualTo(600);
        assertThat(rrd.dataSources().get(7).max()).isEqualTo(100.0);
        assertThat(rrd.dataSources().get(0).lastValue()).isNotNaN();
        assertThat(rrd.archives()).extracting(Archive::cf)
                .containsExactly("AVERAGE", "AVERAGE", "AVERAGE", "MAX", "MIN");
        assertThat(rrd.archives()).extracting(Archive::pdpPerRow).containsExactly(1L, 12L, 288L, 288L, 288L);
        assertThat(rrd.archives()).extracting(a -> a.rows().length).containsExactly(2016, 1488, 366, 366, 366);
        assertThat(rrd.archives().get(0).rows()[0]).hasSize(8);
        long known = Arrays.stream(rrd.archives().get(0).rows())
                .flatMapToDouble(Arrays::stream).filter(v -> !Double.isNaN(v)).count();
        assertThat(known).isGreaterThan(0);
    }

    @ParameterizedTest
    @FieldSource("org.no42.rrdlift.rrd.Fixtures#ARCHES")
    void parsesNeverUpdatedFile(String arch) throws Exception {
        RrdFile rrd = parse(arch, "empty");
        assertThat(rrd.dataSources().get(0).lastValue()).isNaN();
        assertThat(Arrays.stream(rrd.archives().get(0).rows()).flatMapToDouble(Arrays::stream))
                .allMatch(v -> Double.isNaN(v));
    }

    @ParameterizedTest
    @FieldSource("org.no42.rrdlift.rrd.Fixtures#ARCHES")
    void rowTimesEndAtLastUpdateBoundary(String arch) throws Exception {
        RrdFile rrd = parse(arch, "grp");
        Archive daily = rrd.archives().get(2);
        long end = daily.endTime(rrd.step(), rrd.lastUpdate());
        assertThat(end % 86400).isZero();
        assertThat(daily.rowTime(daily.rows().length - 1, rrd.step(), rrd.lastUpdate())).isEqualTo(end);
        assertThat(daily.rowTime(0, rrd.step(), rrd.lastUpdate())).isEqualTo(end - 365L * 86400);
    }
}
