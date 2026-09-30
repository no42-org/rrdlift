/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.no42.rrdlift.labels.LabelIndex;
import org.no42.rrdlift.opennms.FakeOpennms;
import org.no42.rrdlift.opennms.MetaTagConfig;
import org.no42.rrdlift.opennms.OpennmsClient;

class LabelResolverTest {

    static final String P = "org.opennms.timeseries.tin.metatags.";

    static MetaTagConfig config(String... tagsAndExpressions) {
        Map<String, String> props = new HashMap<>();
        for (int i = 0; i < tagsAndExpressions.length; i += 2) {
            props.put(P + "tag." + tagsAndExpressions[i], tagsAndExpressions[i + 1]);
        }
        return MetaTagConfig.fromProperties(props);
    }

    static Map<String, String> live(String resourceId, String name, String... kv) {
        Map<String, String> m = new HashMap<>(Map.of("resourceId", resourceId, "__name__", name, "mtype", "count"));
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @SafeVarargs
    static LabelIndex index(Map<String, String>... sets) {
        LabelIndex index = new LabelIndex();
        for (Map<String, String> s : sets) {
            index.add(s);
        }
        return index;
    }

    @Test
    void copiesNodeTagsFromSiblingsOfTheSameNode() throws Exception {
        LabelIndex idx = index(live("snmp/1/eth0/mib2-interfaces", "ifInOctets", "node", "router1", "location", "dc1"),
                live("snmp/1/mib2-tcp", "tcpActiveOpens", "node", "router1", "location", "dc1"));
        LabelResolver.Result r = new LabelResolver(config("node", "${node:label}", "location", "${node:location}"), idx, null)
                .resolve("snmp/1/eth1/mib2-interfaces", "ifInOctets", "count");
        assertThat(r.labels()).containsEntry("node", "router1").containsEntry("location", "dc1")
                .containsEntry("__name__", "ifInOctets").containsEntry("mtype", "count");
        assertThat(r.sources().get("node")).startsWith("sibling:snmp/1/");
        assertThat(r.unresolved()).isEmpty();
        assertThat(r.nodeGone()).isFalse();
    }

    @Test
    void disagreeingOrPartlyMissingSiblingsLeaveTheTagUnresolved() throws Exception {
        LabelIndex idx = index(live("snmp/1/eth0/mib2-interfaces", "ifInOctets", "location", "dc1"),
                live("snmp/1/eth2/mib2-interfaces", "ifInOctets", "location", "dc2"),
                live("snmp/1/eth3/mib2-interfaces", "ifInOctets"));
        LabelResolver.Result r = new LabelResolver(config("location", "${node:location}"), idx, null)
                .resolve("snmp/1/eth1/mib2-interfaces", "ifInOctets", "count");
        assertThat(r.labels()).doesNotContainKey("location");
        assertThat(r.unresolved()).containsExactly("location");
    }

    @Test
    void keyAbsentOnAllSiblingsIsOmittedNotUnresolved() throws Exception {
        LabelIndex idx = index(live("snmp/1/eth0/mib2-interfaces", "ifInOctets"));
        LabelResolver.Result r = new LabelResolver(config("building", "${asset:building}"), idx, null)
                .resolve("snmp/1/eth1/mib2-interfaces", "ifInOctets", "count");
        assertThat(r.labels()).doesNotContainKey("building");
        assertThat(r.unresolved()).isEmpty();
    }

    @Test
    void interfaceTagsComeFromTheSameDirectoryWithSanitizedName() throws Exception {
        LabelIndex idx = index(live("snmp/1/eth0/mib2-interfaces", "ifInOctets", "if_descr", "Gi0/1"),
                live("snmp/1/eth9/mib2-interfaces", "ifInOctets", "if_descr", "Gi0/9"));
        LabelResolver.Result r = new LabelResolver(config("if-descr", "${interface:if-description}"), idx, null)
                .resolve("snmp/1/eth0/mib2-errors", "ifInErrors", "count");
        assertThat(r.labels()).containsEntry("if_descr", "Gi0/1");
        assertThat(r.sources()).containsEntry("if_descr", "sibling:snmp/1/eth0/mib2-interfaces");
    }

    @Test
    void literalTagsAreConstant() throws Exception {
        LabelResolver.Result r = new LabelResolver(config("env", "prod"), new LabelIndex(), null)
                .resolve("snmp/1/eth0/mib2-interfaces", "ifInOctets", "count");
        assertThat(r.labels()).containsEntry("env", "prod");
        assertThat(r.sources()).containsEntry("env", "constant");
        assertThat(r.unresolved()).isEmpty();
    }

    @Test
    void aliasedDomainDoesNotMixNodes() throws Exception {
        LabelIndex idx = index(live("snmp/uplinks/wan-a/mib2-interfaces", "ifInOctets", "node", "n1"),
                live("snmp/uplinks/wan-b/mib2-interfaces", "ifInOctets", "node", "n2"));
        LabelResolver.Result r = new LabelResolver(config("node", "${node:label}", "lbl", "${resource:label}"), idx, null)
                .resolve("snmp/uplinks/wan-c/mib2-interfaces", "ifInOctets", "count");
        assertThat(r.unresolved()).containsExactly("node");
        assertThat(r.labels()).containsEntry("lbl", "uplinks/wan-c");
    }

    @Test
    void resourceLabelIsTheInterfaceDirectory() throws Exception {
        LabelResolver.Result r = new LabelResolver(config("lbl", "${resource:label}"), new LabelIndex(), null)
                .resolve("snmp/fs/fs1/fid1/eth1-001122334455/mib2-interfaces", "ifInOctets", "count");
        assertThat(r.labels()).containsEntry("lbl", "eth1-001122334455");
        assertThat(r.sources()).containsEntry("lbl", "derived:resource-dir");
        assertThat(LabelResolver.interfaceLabel("snmp/1/mib2-tcp")).isEmpty();
    }

    @Test
    void fallsBackToRestForNodeTagsAndCategories() throws Exception {
        try (FakeOpennms onms = FakeOpennms.start()) {
            onms.addNode(2, null, null, "core2", "dc2", List.of("Routers"), Map.of("building", "B7"));
            OpennmsClient rest = new OpennmsClient(onms.url(), "admin", "admin");
            MetaTagConfig cfg = MetaTagConfig.fromProperties(Map.of(P + "tag.node", "${node:label}",
                    P + "tag.bld", "${asset:building}", P + "exposeCategories", "true"));
            LabelResolver.Result r = new LabelResolver(cfg, new LabelIndex(), rest)
                    .resolve("snmp/2/eth0/mib2-interfaces", "ifInOctets", "count");
            assertThat(r.labels()).containsEntry("node", "core2").containsEntry("bld", "B7")
                    .containsEntry("cat_Routers", "Routers").containsEntry("categories", "Routers");
            assertThat(r.sources()).containsEntry("node", "rest:node/2");
            assertThat(r.unresolved()).isEmpty();
        }
    }

    @Test
    void goneNodeGivesMinimalLabels() throws Exception {
        try (FakeOpennms onms = FakeOpennms.start()) {
            OpennmsClient rest = new OpennmsClient(onms.url(), "admin", "admin");
            LabelResolver.Result r = new LabelResolver(config("node", "${node:label}"), new LabelIndex(), rest)
                    .resolve("snmp/3/eth0/mib2-interfaces", "ifInOctets", "count");
            assertThat(r.nodeGone()).isTrue();
            assertThat(r.labels()).containsOnlyKeys("__name__", "resourceId", "mtype");
        }
    }

    @Test
    void responseResourcesAreMappedToTheirNodeByIp() throws Exception {
        try (FakeOpennms onms = FakeOpennms.start()) {
            onms.addNode(4, null, null, "srv4", "dc4", List.of(), Map.of(), "10.0.0.9");
            OpennmsClient rest = new OpennmsClient(onms.url(), "admin", "admin");
            LabelResolver.Result r = new LabelResolver(config("node", "${node:label}"), new LabelIndex(), rest)
                    .resolve("response/10.0.0.9/icmp", "icmp", "gauge");
            assertThat(r.labels()).containsEntry("node", "srv4");
        }
    }

    @Test
    void withoutConfigEverythingIsPartial() throws Exception {
        LabelResolver.Result r = new LabelResolver(null, new LabelIndex(), null)
                .resolve("snmp/1/eth0/mib2-interfaces", "ifInOctets", "count");
        assertThat(r.unresolved()).containsExactly("meta-tag config not given (--opennms-home)");
        assertThat(r.labels()).containsOnlyKeys("__name__", "resourceId", "mtype");
    }

    @Test
    void aliasedOrphanDoesNotInheritNodeTagsFromAnotherNode() throws Exception {
        LabelIndex idx = index(live("snmp/uplinks/wan-a/mib2-interfaces", "ifInOctets", "node", "n1",
                "cat_Routers", "Routers", "categories", "Routers"));
        MetaTagConfig cfg = MetaTagConfig.fromProperties(Map.of(P + "tag.node", "${node:label}",
                P + "exposeCategories", "true"));
        LabelResolver.Result r = new LabelResolver(cfg, idx, null)
                .resolve("snmp/uplinks/wan-c/mib2-interfaces", "ifInOctets", "count");
        assertThat(r.labels()).doesNotContainKeys("node", "cat_Routers", "categories");
        assertThat(r.unresolved()).contains("node");
    }

    @Test
    void aliasedOrphanUsesItsOwnAliasDirectory() throws Exception {
        LabelIndex idx = index(live("snmp/uplinks/wan-a/mib2-interfaces", "ifInOctets", "node", "n1"),
                live("snmp/uplinks/wan-b/mib2-interfaces", "ifInOctets", "node", "n2"));
        LabelResolver.Result r = new LabelResolver(config("node", "${node:label}"), idx, null)
                .resolve("snmp/uplinks/wan-a/mib2-errors", "ifInErrors", "count");
        assertThat(r.labels()).containsEntry("node", "n1");
    }

    @Test
    void responseMetadataDoesNotSpanServices() throws Exception {
        LabelIndex idx = index(live("response/10.0.0.9/http", "http", "building", "B1"));
        LabelResolver.Result r = new LabelResolver(config("building", "${requisition:building}"), idx, null)
                .resolve("response/10.0.0.9/icmp", "icmp", "gauge");
        assertThat(r.labels()).doesNotContainKey("building");
        assertThat(r.unresolved()).containsExactly("building");
    }
}
