/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.prom;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RemoteWriteEncoderTest {

    @Test
    void roundTripsWithSortedLabels() {
        TimeSeries ts = new TimeSeries(Map.of("resourceId", "snmp/1/x", "__name__", "ifInOctets", "mtype", "count"),
                new long[] {1000, 2000}, new double[] {-5.5, 1.0E19});
        byte[] bytes = RemoteWriteEncoder.encode(List.of(ts));
        List<TimeSeries> back = RemoteWriteEncoder.decode(bytes);
        assertThat(back).hasSize(1);
        assertThat(back.get(0).labels().keySet()).containsExactly("__name__", "mtype", "resourceId");
        assertThat(back.get(0).labels()).isEqualTo(ts.labels());
        assertThat(back.get(0).timesMs()).containsExactly(1000, 2000);
        assertThat(back.get(0).values()).containsExactly(-5.5, 1.0E19);
    }
}
