/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.opennms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RrdLabelsTest {

    @Test
    void followsOpennmsInterfaceDirectoryNaming() {
        assertThat(RrdLabels.label("Gi0/1", "GigabitEthernet0/1", "001122334455", false, false)).isEqualTo("Gi0_1-001122334455");
        assertThat(RrdLabels.label("Gi0/1", "GigabitEthernet0/1", "001122334455", true, false)).isEqualTo("GigabitEthernet0_1-001122334455");
        assertThat(RrdLabels.label("Gi0/1", null, "001122334455", false, true)).isEqualTo("Gi0/1-001122334455");
        assertThat(RrdLabels.label(null, "eth0", null, false, false)).isEqualTo("eth0");
        assertThat(RrdLabels.label("eth0", null, "00:11:22:33:44:55", false, false)).isEqualTo("eth0");
        assertThat(RrdLabels.label("eth0", null, "0011223344", false, false)).isEqualTo("eth0");
    }
}
