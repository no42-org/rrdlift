/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.cli;

import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import org.no42.rrdlift.labels.LabelIndex;
import org.no42.rrdlift.prom.PromClient;
import org.no42.rrdlift.prom.Selectors;
import org.no42.rrdlift.repo.RepositoryWalker;
import org.no42.rrdlift.repo.WorkItem;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "snapshot-labels", mixinStandardHelpOptions = true,
        description = "Copies the labels of live series from the backend into a label index file.")
public final class SnapshotLabelsCommand implements Callable<Integer> {

    @Option(names = "--rrd-dir", defaultValue = "/opt/opennms/share/rrd")
    Path rrdDir;

    @Mixin
    Connection connection;

    @Option(names = "--since-days", defaultValue = "7", description = "Look for series active in the last N days")
    int sinceDays;

    @Option(names = "--out", defaultValue = "labels-prometheus.json")
    Path out;

    @Override
    public Integer call() {
        try {
            Set<String> prefixes = new TreeSet<>();
            for (WorkItem item : RepositoryWalker.walk(rrdDir).items()) {
                prefixes.add(RepositoryWalker.nodePrefix(item.resourceId()));
            }
            PromClient client = connection.client();
            long now = System.currentTimeMillis() / 1000;
            LabelIndex index = new LabelIndex();
            for (String prefix : prefixes) {
                client.series(Selectors.resourcePrefix(prefix), now - sinceDays * 86400L, now + 1).forEach(index::add);
            }
            index.save(out);
            System.out.printf("snapshot-labels: %d label sets for %d keys from %d node directories -> %s%n",
                    index.labelSets(), index.keys(), prefixes.size(), out);
            return 0;
        } catch (Exception e) {
            System.err.println("snapshot-labels: " + e.getMessage());
            return 1;
        }
    }
}
