/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import org.no42.rrdlift.labels.LabelIndex;
import org.no42.rrdlift.plan.EntryClass;
import org.no42.rrdlift.plan.Plan;
import org.no42.rrdlift.plan.PlanEntry;
import org.no42.rrdlift.plan.Planner;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "plan", mixinStandardHelpOptions = true,
        description = "Matches every RRD data source to a label set and reports what a backfill would write.")
public final class PlanCommand implements Callable<Integer> {

    @Option(names = "--rrd-dir", defaultValue = "/opt/opennms/share/rrd")
    Path rrdDir;

    @Option(names = "--labels", required = true, description = "Label index from snapshot-labels")
    Path labels;

    @Option(names = "--opennms-labels", description = "Label index from opennms:tss-export-labels")
    Path opennmsLabels;

    @Option(names = "--skip-orphans", description = "Do not write data sources without a live or exported label set")
    boolean skipOrphans;

    @Mixin
    ReaderOptions readerOptions;

    @Option(names = "--rate", defaultValue = "20000", description = "Samples per second, for the duration estimate")
    long rate;

    @Option(names = "--out", defaultValue = "plan.json")
    Path out;

    @Option(names = "--orphans", defaultValue = "orphans.txt")
    Path orphans;

    @Option(names = "--not-migrated", defaultValue = "not-migrated.txt",
            description = "Tab-separated list of every data source or file that will not be written")
    Path notMigrated;

    @Override
    public Integer call() {
        try {
            LabelIndex onms = opennmsLabels == null ? null : LabelIndex.load(opennmsLabels);
            Plan plan = Planner.plan(rrdDir, readerOptions.opener(), LabelIndex.load(labels), onms, skipOrphans);
            plan.save(out);
            TreeSet<String> orphanIds = new TreeSet<>();
            List<String> unwritten = new ArrayList<>();
            Map<EntryClass, Integer> counts = new EnumMap<>(EntryClass.class);
            Map<String, Integer> byRoot = new TreeMap<>();
            long samples = 0;
            for (PlanEntry e : plan.entries()) {
                counts.merge(e.entryClass(), 1, Integer::sum);
                if (e.resourceId() != null) {
                    byRoot.merge(e.resourceId().split("/")[0], 1, Integer::sum);
                }
                if (e.entryClass().isOrphan()) {
                    orphanIds.add(e.resourceId());
                }
                if (e.labels() != null) {
                    samples += e.samples();
                } else {
                    unwritten.add(String.join("\t", e.entryClass().name(),
                            e.resourceId() != null ? e.resourceId() : e.file(),
                            e.dsName() != null ? e.dsName() : "", e.note() != null ? e.note() : ""));
                }
            }
            Files.write(orphans, orphanIds);
            Collections.sort(unwritten);
            List<String> notMigratedLines = new ArrayList<>();
            notMigratedLines.add("# class\tresource\tds\tnote");
            notMigratedLines.addAll(unwritten);
            Files.write(notMigrated, notMigratedLines);
            System.out.printf("plan: %d files, %d entries%n", plan.writableByFile().size(), plan.entries().size());
            StringBuilder classes = new StringBuilder(" ");
            for (EntryClass c : EntryClass.values()) {
                classes.append(' ').append(c).append(' ').append(counts.getOrDefault(c, 0));
            }
            System.out.println(classes);
            byRoot.forEach((root, n) -> System.out.printf("  %s: %d entries%n", root, n));
            long seconds = rate > 0 ? samples / rate : 0;
            System.out.printf("  samples to write: %d, oldest sample: %s, estimate at %d samples/s: %dh %dm%n",
                    samples, plan.oldestSampleSec() == 0 ? "none" : Instant.ofEpochSecond(plan.oldestSampleSec()),
                    rate, seconds / 3600, seconds % 3600 / 60);
            System.out.printf("  plan -> %s, orphans -> %s (%d resources)%n", out, orphans, orphanIds.size());
            System.out.printf("  not migrated -> %s (%d lines)%n", notMigrated, unwritten.size());
            return 0;
        } catch (Exception e) {
            System.err.println("plan: " + e.getMessage());
            return 1;
        }
    }
}
