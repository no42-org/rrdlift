/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.labels;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.no42.rrdlift.repo.RepositoryWalker;
import org.no42.rrdlift.repo.WorkItem;
import org.no42.rrdlift.rrd.RrdOpener;

/** What a label snapshot was taken against. configHash and expectedKeys are null without --opennms-home. */
public record SnapshotInfo(long snapshotSec, long cutoverSec, String backendUrl, String configHash,
                           List<String> expectedKeys) {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static Path sidecarOf(Path labelsFile) {
        String name = labelsFile.getFileName().toString();
        String base = name.endsWith(".json") ? name.substring(0, name.length() - ".json".length()) : name;
        return labelsFile.resolveSibling(base + ".meta.json");
    }

    public void save(Path file) throws IOException {
        JSON.writeValue(file.toFile(), this);
    }

    public static SnapshotInfo load(Path file) throws IOException {
        return JSON.readValue(file.toFile(), SnapshotInfo.class);
    }

    /** Newest lastUpdate of every readable RRD file: the moment RRD writing stopped. 0 when there is none. */
    public static long cutover(Path rrdDir, RrdOpener opener) throws IOException {
        long newest = 0;
        for (WorkItem item : RepositoryWalker.walk(rrdDir).items()) {
            try {
                newest = Math.max(newest, opener.open(item.file()).lastUpdate());
            } catch (IOException | RuntimeException e) {
                // unreadable files are reported by plan; they do not define the cutover
            }
        }
        return newest;
    }
}
