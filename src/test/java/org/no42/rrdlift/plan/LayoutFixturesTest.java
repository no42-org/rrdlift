/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.no42.rrdlift.labels.LabelIndex;
import org.no42.rrdlift.opennms.MetaTagConfig;
import org.no42.rrdlift.repo.RepositoryWalker;
import org.no42.rrdlift.repo.WalkResult;
import org.no42.rrdlift.repo.WorkItem;
import org.no42.rrdlift.rrd.RrdOpener;

/** Trees written by Horizon 36.0.4 in the #7 storage-layout lab, one per storeByForeignSource x storeByGroup. */
class LayoutFixturesTest {

    static final RrdOpener OPENER = new RrdOpener(RrdOpener.Mode.NATIVE, null);

    static Stream<Arguments> layouts() {
        return Stream.of(
                Arguments.of("fs-group", "snmp/fs/lab/a", "172.31.71.11"),
                Arguments.of("fs-metric", "snmp/fs/lab/a", "172.31.72.11"),
                Arguments.of("id-group", "snmp/3", "172.31.73.11"),
                Arguments.of("id-metric", "snmp/2", "172.31.74.11"));
    }

    static Path root(String layout) {
        return Path.of("src/test/resources/fixtures/layouts", layout);
    }

    static String eth0(String node) {
        return node + "/eth0-02420a0b0202/mib2-X-interfaces";
    }

    static String eth2(String node) {
        return node + "/eth2-02420a0b0404/mib2-X-interfaces";
    }

    @ParameterizedTest
    @MethodSource("layouts")
    void walkerFindsTheResourceIdsOpennmsWritesLive(String layout, String node, String ip) throws Exception {
        WalkResult result = RepositoryWalker.walk(root(layout).resolve("rrd"));

        assertThat(result.skipped()).isEmpty();
        assertThat(result.items()).extracting(WorkItem::resourceId).containsOnly(
                "response/" + ip + "/icmp", eth0(node), eth2(node), node + "/ucd-loadavg");
        LabelIndex live = LabelIndex.load(root(layout).resolve("labels-prometheus.json"));
        assertThat(live.all()).extracting(l -> l.get("resourceId")).containsOnly(
                "response/" + ip + "/icmp", eth0(node), node + "/ucd-loadavg");
    }

    @ParameterizedTest
    @MethodSource("layouts")
    void planMatchesLiveSeriesAndResolvesTheOrphanFromItsSiblings(String layout, String node, String ip)
            throws Exception {
        LabelIndex live = LabelIndex.load(root(layout).resolve("labels-prometheus.json"));
        MetaTagConfig config = MetaTagConfig.load(root(layout).resolve("opennms"));
        Plan plan = Planner.plan(root(layout).resolve("rrd"), OPENER, live, null,
                new PlanOptions(false, false, false, config, null));
        Map<String, PlanEntry> entries = plan.entries().stream()
                .collect(Collectors.toMap(e -> e.resourceId() + "|" + e.dsName(), Function.identity()));

        assertThat(entries.values()).filteredOn(e -> !e.resourceId().equals(eth2(node)))
                .hasSize(live.all().size())
                .allSatisfy(e -> assertThat(e.entryClass()).isEqualTo(EntryClass.MATCHED));
        for (String ds : new String[] {"ifHCInOctets", "ifHCOutOctets"}) {
            PlanEntry matched = entries.get(eth0(node) + "|" + ds);
            PlanEntry orphan = entries.get(eth2(node) + "|" + ds);
            Map<String, String> expected = new TreeMap<>(matched.labels());
            expected.remove("ifDescr");
            expected.put("resourceId", eth2(node));

            assertThat(orphan.entryClass()).isEqualTo(EntryClass.ORPHAN_PARTIAL);
            assertThat(orphan.note()).isEqualTo("unresolved: ifDescr");
            assertThat(orphan.labels()).isEqualTo(expected);
        }
    }
}
