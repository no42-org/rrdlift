/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.opennms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetaTagConfigTest {

    static final String P = "org.opennms.timeseries.tin.metatags.";

    @Test
    void laterPropertiesFilesWin(@TempDir Path home) throws Exception {
        Path etc = Files.createDirectories(home.resolve("etc/opennms.properties.d"));
        Files.writeString(home.resolve("etc/opennms.properties"),
                P + "tag.node=${node:label}\n" + P + "tag.location=${node:location}\n");
        Files.writeString(etc.resolve("a-cortex.properties"), P + "tag.location=${node:foreign-source}\n");
        Files.writeString(etc.resolve("z-last.properties"),
                P + "tag.location=${node:location}\n" + P + "exposeCategories=true\n"
                        + "org.opennms.core.utils.preferIfDescr=true\n");
        Files.writeString(etc.resolve("ignored.txt"), P + "tag.nope=x\n");

        MetaTagConfig c = MetaTagConfig.load(home);

        assertThat(c.tags()).containsExactly(Map.entry("location", "${node:location}"), Map.entry("node", "${node:label}"));
        assertThat(c.exposeCategories()).isTrue();
        assertThat(c.preferIfDescr()).isTrue();
        assertThat(c.dontSanitizeIfName()).isFalse();
    }

    @Test
    void missingEtcGivesEmptyConfig(@TempDir Path home) throws Exception {
        assertThat(MetaTagConfig.load(home).tags()).isEmpty();
    }

    @Test
    void classifiesScopesByNarrowestContext() {
        assertThat(MetaTagConfig.scopeOf("${node:label}")).isEqualTo(TagScope.NODE);
        assertThat(MetaTagConfig.scopeOf("${asset:building}")).isEqualTo(TagScope.NODE);
        assertThat(MetaTagConfig.scopeOf("${interface:if-description}")).isEqualTo(TagScope.INTERFACE);
        assertThat(MetaTagConfig.scopeOf("${requisition:team}")).isEqualTo(TagScope.METADATA);
        assertThat(MetaTagConfig.scopeOf("${service:name}")).isEqualTo(TagScope.SERVICE);
        assertThat(MetaTagConfig.scopeOf("${resource:label}")).isEqualTo(TagScope.RESOURCE);
        assertThat(MetaTagConfig.scopeOf("${node:label}-${interface:if-name}")).isEqualTo(TagScope.INTERFACE);
        assertThat(MetaTagConfig.scopeOf("${interface:if-alias|node:label}")).isEqualTo(TagScope.INTERFACE);
        assertThat(MetaTagConfig.scopeOf("${node:label|unknown}")).isEqualTo(TagScope.NODE);
        assertThat(MetaTagConfig.scopeOf("prod")).isEqualTo(TagScope.CONSTANT);
        assertThat(MetaTagConfig.scopeOf("${node:label")).isEqualTo(TagScope.UNCLASSIFIED);
        assertThat(MetaTagConfig.scopeOf("${nocolon}")).isEqualTo(TagScope.UNCLASSIFIED);
    }

    @Test
    void hashIgnoresInsertionOrderAndSeesChanges() {
        Map<String, String> a = new LinkedHashMap<>();
        a.put(P + "tag.node", "${node:label}");
        a.put(P + "tag.location", "${node:location}");
        Map<String, String> b = new LinkedHashMap<>();
        b.put(P + "tag.location", "${node:location}");
        b.put(P + "tag.node", "${node:label}");
        assertThat(MetaTagConfig.fromProperties(a).hash()).isEqualTo(MetaTagConfig.fromProperties(b).hash()).hasSize(64);
        b.put(P + "exposeCategories", "true");
        assertThat(MetaTagConfig.fromProperties(b).hash()).isNotEqualTo(MetaTagConfig.fromProperties(a).hash());
    }

    @Test
    void expectedKeysAreSanitizedLabelNames() {
        MetaTagConfig c = MetaTagConfig.fromProperties(Map.of(
                P + "tag.if-descr", "${interface:if-description}", P + "tag.node", "${node:label}",
                P + "exposeCategories", "true"));
        assertThat(c.expectedKeys()).containsExactly("categories", "if_descr", "node");
        assertThat(c.scopes()).containsEntry("if-descr", TagScope.INTERFACE);
    }

    @Test
    void nonexistentHomeIsRejected(@TempDir Path tmp) {
        Path missing = tmp.resolve("nope");
        assertThatThrownBy(() -> MetaTagConfig.load(missing))
                .isInstanceOf(NoSuchFileException.class)
                .hasMessageContaining(missing.toString());
    }
}
