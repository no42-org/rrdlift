/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import org.no42.rrdlift.labels.LabelIndex;
import org.no42.rrdlift.labels.SnapshotInfo;
import org.no42.rrdlift.opennms.MetaTagConfig;
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

    private static final int USAGE = 64;

    @Option(names = "--rrd-dir", defaultValue = "/opt/opennms/share/rrd")
    Path rrdDir;

    @Mixin
    Connection connection;

    @Option(names = "--since-days", defaultValue = "7", description = "Look for series active in the last N days")
    int sinceDays;

    @Option(names = "--out", defaultValue = "labels-prometheus.json")
    Path out;

    @Mixin
    ReaderOptions readerOptions;

    @Option(names = "--opennms-home", description = "OpenNMS home; records the meta-tag config hash and expected keys")
    Path opennmsHome;

    @Override
    public Integer call() {
        if (connection.readUrl == null) {
            System.err.println("snapshot-labels: --read-url is required");
            return USAGE;
        }
        try {
            Set<String> prefixes = new TreeSet<>();
            for (WorkItem item : RepositoryWalker.walk(rrdDir).items()) {
                prefixes.add(RepositoryWalker.nodePrefix(item.resourceId()));
            }
            PromClient client = connection.client();
            long now = System.currentTimeMillis() / 1000;
            long cutover = SnapshotInfo.cutover(rrdDir, readerOptions.opener());
            // rrdlift's own backfill writes samples up to the cutover, so only samples after it prove a live series
            long start = now - sinceDays * 86400L;
            if (cutover > 0) {
                start = Math.max(start, cutover + 1);
            }
            LabelIndex index = new LabelIndex();
            for (String prefix : prefixes) {
                client.series(Selectors.resourcePrefix(prefix), start, now + 1).forEach(index::add);
            }
            MetaTagConfig config = opennmsHome == null ? null : MetaTagConfig.load(opennmsHome);
            SnapshotInfo info = new SnapshotInfo(now, cutover,
                    connection.readUrl.toString(), config == null ? null : config.hash(),
                    config == null ? null : List.copyOf(config.expectedKeys()));
            index.save(out);
            info.save(SnapshotInfo.sidecarOf(out));
            System.out.printf("snapshot-labels: %d label sets for %d keys from %d node directories -> %s, sidecar -> %s%n",
                    index.labelSets(), index.keys(), prefixes.size(), out, SnapshotInfo.sidecarOf(out));
            return 0;
        } catch (Exception e) {
            System.err.println("snapshot-labels: " + e.getMessage());
            return 1;
        }
    }
}
