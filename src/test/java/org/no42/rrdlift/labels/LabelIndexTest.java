/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.labels;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LabelIndexTest {

    static final Map<String, String> LIVE = Map.of(
            "__name__", "ifInOctets", "resourceId", "snmp/1/eth0/mib2-interfaces", "mtype", "count", "node", "n1");

    @Test
    void keysByResourceIdAndSanitizedName() {
        LabelIndex index = new LabelIndex();
        index.add(LIVE);
        index.add(LIVE);
        index.add(Map.of("__name__", "orphanless")); // no resourceId: ignored
        assertThat(index.keys()).isEqualTo(1);
        assertThat(index.labelSets()).isEqualTo(1);
        assertThat(index.lookup(SeriesKey.forDataSource("snmp/1/eth0/mib2-interfaces", "ifInOctets")))
                .containsExactly(LIVE);
        assertThat(index.lookup(new SeriesKey("nope", "nope"))).isEmpty();
    }

    @Test
    void keepsDifferentLabelSetsForTheSameKey() {
        LabelIndex index = new LabelIndex();
        index.add(LIVE);
        index.add(Map.of("__name__", "ifInOctets", "resourceId", "snmp/1/eth0/mib2-interfaces",
                "mtype", "count", "node", "renamed"));
        assertThat(index.lookup(SeriesKey.of(LIVE))).hasSize(2);
    }

    @Test
    void forDataSourceSanitizesLikeThePlugin() {
        SeriesKey key = SeriesKey.forDataSource("snmp/1/x", "9ifHC-In.Octets");
        assertThat(key.metricName()).isEqualTo("_ifHC_In_Octets");
    }

    @Test
    void roundTripsThroughJson(@TempDir Path tmp) throws Exception {
        LabelIndex index = new LabelIndex();
        index.add(LIVE);
        Path file = tmp.resolve("labels.json");
        index.save(file);
        LabelIndex loaded = LabelIndex.load(file);
        assertThat(loaded.lookup(SeriesKey.of(LIVE))).containsExactly(LIVE);
    }
}
