/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import org.no42.rrdlift.plan.Plan;
import org.no42.rrdlift.plan.PlanEntry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "explain", mixinStandardHelpOptions = true,
        description = "Prints the planned labels of one resource and where each label came from.")
public final class ExplainCommand implements Callable<Integer> {

    @Option(names = "--plan", required = true)
    Path plan;

    @Option(names = "--ds", description = "Only this data source")
    String ds;

    @Parameters(index = "0", paramLabel = "RESOURCE_ID")
    String resourceId;

    @Override
    public Integer call() {
        try {
            List<PlanEntry> hits = Plan.load(plan).entries().stream()
                    .filter(e -> resourceId.equals(e.resourceId()) && (ds == null || ds.equals(e.dsName()))).toList();
            if (hits.isEmpty()) {
                System.err.println("explain: " + resourceId + " is not in the plan");
                return 1;
            }
            for (PlanEntry e : hits) {
                System.out.println(e.resourceId() + " " + (e.dsName() == null ? "-" : e.dsName()) + ": " + e.entryClass()
                        + (e.labels() == null ? " (not written)" : ""));
                if (e.labels() != null) {
                    Map<String, String> sources = e.labelSources() == null ? Map.of() : e.labelSources();
                    new TreeMap<>(e.labels()).forEach((k, v) ->
                            System.out.println("  " + k + "=" + v + "  (" + sources.getOrDefault(k, "unknown") + ")"));
                }
                if (e.candidates() != null) {
                    e.candidates().forEach(c -> System.out.println("  candidate: " + new TreeMap<>(c)));
                }
                if (e.note() != null) {
                    System.out.println("  note: " + e.note());
                }
            }
            return 0;
        } catch (Exception e) {
            System.err.println("explain: " + e.getMessage());
            return 1;
        }
    }
}
