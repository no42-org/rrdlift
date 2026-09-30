/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.opennms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NodeExpressionsTest {

    static final OpennmsClient.NodeInfo N = new OpennmsClient.NodeInfo(7, "router1", "dc1", "fs1", "fid1",
            List.of("Routers"), Map.of("modelNumber", "MX480"));

    @Test
    void evaluatesSupportedAttributes() {
        assertThat(NodeExpressions.eval("${node:label}", N)).contains("router1");
        assertThat(NodeExpressions.eval("${node:location}", N)).contains("dc1");
        assertThat(NodeExpressions.eval("${node:foreign-source}/${node:foreign-id}", N)).contains("fs1/fid1");
        assertThat(NodeExpressions.eval("${node:criteria}", N)).contains("fs1:fid1");
        assertThat(NodeExpressions.eval("${node:label}-${asset:model-number}", N)).contains("router1-MX480");
    }

    @Test
    void appliesFallbacksAndDefaults() {
        assertThat(NodeExpressions.eval("${asset:building|node:label}", N)).contains("router1");
        assertThat(NodeExpressions.eval("${asset:building|unknown}", N)).contains("unknown");
        assertThat(NodeExpressions.eval("${asset:building}", N)).contains("");
    }

    @Test
    void criteriaFallsBackToIdWithoutForeignId() {
        OpennmsClient.NodeInfo plain = new OpennmsClient.NodeInfo(7, "r", "dc", null, null, List.of(), Map.of());
        assertThat(NodeExpressions.eval("${node:criteria}", plain)).contains("7");
    }

    @Test
    void unsupportedAttributesAreEmpty() {
        assertThat(NodeExpressions.eval("${node:geohash}", N)).isEmpty();
        assertThat(NodeExpressions.eval("${interface:if-description}", N)).isEmpty();
        assertThat(NodeExpressions.eval("${requisition:team}", N)).isEmpty();
        assertThat(NodeExpressions.eval("${node:label", N)).isEmpty();
    }
}
