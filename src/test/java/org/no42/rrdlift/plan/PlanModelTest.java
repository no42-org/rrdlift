/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.no42.rrdlift.labels.LabelIndex;
import org.no42.rrdlift.labels.SnapshotInfo;

class PlanModelTest {

    static PlanEntry entry(String ds, Map<String, String> labels) {
        return new PlanEntry("f.rrd", "snmp/1/x/g", ds, "gauge", EntryClass.ORPHAN_COMPLETE, labels, 3, null,
                Map.of("node", "sibling:snmp/1/y/g"), null);
    }

    @Test
    void roundTripsNewFields(@TempDir Path tmp) throws Exception {
        Plan plan = new Plan("rrd", 1, 2, List.of(entry("a", Map.of("__name__", "a"))))
                .withSnapshot(new SnapshotInfo(10, 9, "http://b", "h", List.of("node")));
        Path file = tmp.resolve("plan.json");
        plan.save(file);
        assertThat(Plan.load(file)).isEqualTo(plan);
    }

    @Test
    void loadsPlanWrittenBeforeTheseFields(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("old.json");
        Files.writeString(file, """
                {"rrdDir":"rrd","createdAt":1,"oldestSampleSec":2,"entries":[
                 {"file":"f.rrd","resourceId":"snmp/1/x/g","dsName":"a","mtype":"gauge","entryClass":"ORPHAN",
                  "labels":{"__name__":"a"},"samples":3,"note":null}]}
                """);
        Plan plan = Plan.load(file);
        assertThat(plan.snapshot()).isNull();
        assertThat(plan.entries().get(0).entryClass().isOrphan()).isTrue();
        assertThat(plan.entries().get(0).labelSources()).isNull();
    }

    @Test
    void isOrphanCoversAllOrphanClasses() {
        assertThat(EntryClass.ORPHAN_PARTIAL.isOrphan()).isTrue();
        assertThat(EntryClass.ORPHAN_MINIMAL.isOrphan()).isTrue();
        assertThat(EntryClass.PENDING.isOrphan()).isFalse();
        assertThat(EntryClass.MATCHED.isOrphan()).isFalse();
    }

    @Test
    void labelHashIsOrderIndependentAndSensitive() {
        PlanEntry a = entry("a", Map.of("__name__", "a", "node", "n1"));
        PlanEntry b = entry("b", Map.of("__name__", "b"));
        assertThat(LabelHash.of(List.of(a, b))).isEqualTo(LabelHash.of(List.of(b, a))).hasSize(64);
        PlanEntry a2 = entry("a", Map.of("__name__", "a", "node", "n2"));
        assertThat(LabelHash.of(List.of(a2, b))).isNotEqualTo(LabelHash.of(List.of(a, b)));
    }

    @Test
    void labelIndexAllReturnsEverySet() {
        LabelIndex index = new LabelIndex();
        index.add(Map.of("__name__", "a", "resourceId", "r1"));
        index.add(Map.of("__name__", "a", "resourceId", "r1", "node", "x"));
        index.add(Map.of("__name__", "b", "resourceId", "r2"));
        assertThat(index.all()).hasSize(3);
    }
}
