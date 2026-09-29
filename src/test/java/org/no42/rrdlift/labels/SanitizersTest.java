/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.labels;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.opennms.timeseries.cortex.CortexTSS;

/** Pins our copy against the published plugin (test dependency). */
class SanitizersTest {

    static final List<String> INPUTS = List.of(
            "ifHCInOctets", "9ifHC-In.Octets", "a:b:c", "_x", "", "tcpActiveOpens", "résumé",
            "JMX_java.lang:type=GarbageCollector.name.PS Scavenge", "snmp/1/eth0-0011/mib2-interfaces",
            "x".repeat(3000), "ICMP/10.0.0.1");

    @ParameterizedTest
    @FieldSource("INPUTS")
    void matchesPlugin(String in) {
        assertThat(Sanitizers.sanitizeMetricName(in)).isEqualTo(CortexTSS.sanitizeMetricName(in));
        assertThat(Sanitizers.sanitizeLabelName(in)).isEqualTo(CortexTSS.sanitizeLabelName(in));
        assertThat(Sanitizers.sanitizeLabelValue(in)).isEqualTo(CortexTSS.sanitizeLabelValue(in));
    }
}
