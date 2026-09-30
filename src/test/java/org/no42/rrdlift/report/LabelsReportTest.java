/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.no42.rrdlift.labels.SnapshotInfo;
import org.no42.rrdlift.opennms.MetaTagConfig;
import org.no42.rrdlift.plan.EntryClass;
import org.no42.rrdlift.plan.Plan;
import org.no42.rrdlift.plan.PlanEntry;

class LabelsReportTest {

    static final MetaTagConfig CONFIG = MetaTagConfig.fromProperties(Map.of(
            "org.opennms.timeseries.tin.metatags.tag.node", "${node:label}",
            "org.opennms.timeseries.tin.metatags.tag.odd", "${node:label"));

    static Plan plan(List<PlanEntry> entries) {
        return new Plan("rrd", 1_800_000_000L, 0, entries,
                new SnapshotInfo(1_800_000_000L, 1_799_999_000L, "http://prom:9090", CONFIG.hash(), List.of("node")));
    }

    static List<PlanEntry> sample() {
        List<PlanEntry> e = new ArrayList<>();
        e.add(new PlanEntry("a.rrd", "snmp/1/eth0/mib2-interfaces", "ifInOctets", "count", EntryClass.MATCHED,
                Map.of("__name__", "ifInOctets", "resourceId", "snmp/1/eth0/mib2-interfaces", "mtype", "count", "node", "n1"),
                10, null, Map.of("node", "live"), null));
        e.add(new PlanEntry("b.rrd", "snmp/2/eth0/mib2-interfaces", "ifInOctets", "count", EntryClass.ORPHAN_PARTIAL,
                Map.of("__name__", "ifInOctets", "resourceId", "snmp/2/eth0/mib2-interfaces", "mtype", "count"),
                10, "unresolved: node", Map.of(), null));
        e.add(new PlanEntry("c.rrd", "snmp/3/eth0/mib2-interfaces", "ifInOctets", "count", EntryClass.AMBIGUOUS, null, 10,
                "2 live label sets", null, List.of(Map.of("node", "<script>alert(1)</script>"), Map.of("node", "a&b \"q\"\nline"))));
        e.add(new PlanEntry("d.rrd", "snmp/4/eth0/mib2-interfaces", "ifInOctets", "count", EntryClass.PENDING, null, 10,
                "still written at cutover; rerun snapshot-labels and plan after 2027-01-15T08:00:00Z", null, null));
        return e;
    }

    @Test
    void rendersEverySection() {
        String html = LabelsReport.render(plan(sample()), CONFIG, Boolean.TRUE);
        assertThat(html).startsWith("<!doctype html>");
        for (String id : List.of("header", "tags", "classes", "coverage", "attention", "pending")) {
            assertThat(html).contains("<section id=\"" + id + "\"");
        }
        assertThat(html).contains("http://prom:9090").contains(CONFIG.hash()).contains("ORPHAN_PARTIAL")
                .contains("snmp/2").contains("2027-01-15T08:00:00Z").contains("UNCLASSIFIED");
    }

    @Test
    void escapesEveryValue() {
        String html = LabelsReport.render(plan(sample()), CONFIG, null);
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;").doesNotContain("<script>alert(1)");
        assertThat(html).contains("a&amp;b &quot;q&quot;");
        assertThat(html.split("<script", -1)).hasSize(2); // exactly one: the inline table script
    }

    @Test
    void loadsNothingExternal() {
        String html = LabelsReport.render(plan(sample()), CONFIG, null);
        assertThat(html).doesNotContain(" src=").doesNotContain("<link").doesNotContain("@import").doesNotContain("url(");
    }

    @Test
    void capsLongLists() {
        List<PlanEntry> e = new ArrayList<>();
        for (int i = 0; i < 1005; i++) {
            e.add(new PlanEntry("f" + i, "snmp/" + i + "/eth0/g", "x", "gauge", EntryClass.ORPHAN_PARTIAL,
                    Map.of("__name__", "x"), 1, "unresolved: node", Map.of(), null));
        }
        String html = LabelsReport.render(plan(e), CONFIG, null);
        assertThat(html).contains("Showing 1000 of 1005. Full detail: not-migrated.txt, plan.json, rrdlift explain.");
    }

    @Test
    void rendersPlanFrom01WithoutSnapshotOrSources() {
        List<PlanEntry> e = List.of(new PlanEntry("a.rrd", "snmp/1/eth0/g", "x", "gauge", EntryClass.ORPHAN,
                Map.of("__name__", "x"), 1, null));
        String html = LabelsReport.render(new Plan("rrd", 1L, 0, e), null, null);
        assertThat(html).contains("<section id=\"header\"").contains("ORPHAN");
    }
}
