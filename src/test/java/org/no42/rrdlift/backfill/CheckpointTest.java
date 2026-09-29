/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckpointTest {

    @Test
    void lastLinePerFileWinsAndPassesAreSeparate(@TempDir Path tmp) throws Exception {
        Checkpoint cp = new Checkpoint(tmp);
        cp.append(new Checkpoint.Entry("a.rrd", "RECENT", Checkpoint.Status.FAILED, 0, 5, "HTTP 400"));
        cp.append(new Checkpoint.Entry("a.rrd", "RECENT", Checkpoint.Status.DONE, 10, 1, null));
        cp.append(new Checkpoint.Entry("b.rrd", "OLDER", Checkpoint.Status.DONE, 3, 2, null));

        Map<String, Checkpoint.Entry> recent = new Checkpoint(tmp).load("RECENT");
        assertThat(recent).containsOnlyKeys("a.rrd");
        assertThat(recent.get("a.rrd").status()).isEqualTo(Checkpoint.Status.DONE);
        assertThat(new Checkpoint(tmp).load("OLDER").get("b.rrd").attempts()).isEqualTo(2);
        assertThat(new Checkpoint(tmp).load("ALL")).isEmpty();
    }

    @Test
    void ignoresALineCutShortByACrash(@TempDir Path tmp) throws Exception {
        Checkpoint cp = new Checkpoint(tmp);
        cp.append(new Checkpoint.Entry("a.rrd", "RECENT", Checkpoint.Status.DONE, 10, 1, null));
        Files.writeString(tmp.resolve("state/RECENT.jsonl"), "{\"file\":\"b.rrd\",\"pa", StandardOpenOption.APPEND);
        assertThat(new Checkpoint(tmp).load("RECENT")).containsOnlyKeys("a.rrd");
    }
}
