/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.rrd;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.jrobin.core.RrdDb;
import org.jrobin.core.RrdDef;
import org.jrobin.core.Sample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JrobinReaderTest {

    static final long T0 = 1_700_000_100L - 1_700_000_100L % 300;

    /** Gauge g = i and counter c = 1000 + 300 i, updated 100 s after each boundary, 20 updates. */
    static Path create(Path dir) throws Exception {
        Path file = dir.resolve("g.jrb");
        RrdDef def = new RrdDef(file.toString(), T0, 300);
        def.addDatasource("g", "GAUGE", 600, Double.NaN, Double.NaN);
        def.addDatasource("c", "COUNTER", 600, 0, Double.NaN);
        def.addArchive("AVERAGE", 0.5, 1, 10);
        def.addArchive("MAX", 0.5, 12, 5);
        RrdDb db = new RrdDb(def);
        for (int i = 1; i <= 20; i++) {
            Sample s = db.createSample(T0 + 300L * i + 100);
            s.setValue(0, i);
            s.setValue(1, 1000 + 300.0 * i);
            s.update();
        }
        db.close();
        return file;
    }

    @Test
    void readsDefinitionsStateAndRowsOldestFirst(@TempDir Path tmp) throws Exception {
        RrdFile rrd = JrobinReader.read(create(tmp));

        assertThat(rrd.version()).isEqualTo("JRobin, version 0.1");
        assertThat(rrd.step()).isEqualTo(300);
        assertThat(rrd.lastUpdate()).isEqualTo(T0 + 6100);
        assertThat(rrd.dataSources()).extracting(DataSource::name).containsExactly("g", "c");
        assertThat(rrd.dataSources()).extracting(DataSource::type).containsExactly("GAUGE", "COUNTER");

        DataSource c = rrd.dataSources().get(1);
        assertThat(c.heartbeat()).isEqualTo(600);
        assertThat(c.lastValue()).isEqualTo(7000.0);
        assertThat(c.pdpValue()).isEqualTo(100.0);

        Archive avg = rrd.archives().get(0);
        assertThat(avg.cf()).isEqualTo("AVERAGE");
        assertThat(avg.pdpPerRow()).isEqualTo(1);
        assertThat(avg.rows()).hasNumberOfRows(10);
        assertThat(avg.endTime(rrd.step(), rrd.lastUpdate())).isEqualTo(T0 + 6000);
        // oldest first: gauge rows increase, newest row is the step ending at T0 + 6000
        assertThat(avg.rows()[0][0]).isLessThan(avg.rows()[9][0]);
        assertThat(avg.rows()[9][0]).isBetween(19.0, 20.0);
        assertThat(avg.rows()[9][1]).isEqualTo(1.0);
        assertThat(rrd.archives().get(1).cf()).isEqualTo("MAX");
    }
}
