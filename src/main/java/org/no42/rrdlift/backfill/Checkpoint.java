/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.backfill;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/** Per-pass progress: one JSON line per finished file; the last line for a file wins. */
public final class Checkpoint {

    public enum Status { DONE, FAILED }

    public record Entry(String file, String pass, Status status, long samples, int attempts, String error) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path dir;

    public Checkpoint(Path stateDir) {
        this.dir = stateDir.resolve("state");
    }

    public synchronized void append(Entry e) throws IOException {
        Files.createDirectories(dir);
        byte[] line = (JSON.writeValueAsString(e) + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(dir.resolve(e.pass() + ".jsonl"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(ByteBuffer.wrap(line));
            ch.force(false);
        }
    }

    public Map<String, Entry> load(String pass) throws IOException {
        Map<String, Entry> out = new HashMap<>();
        Path file = dir.resolve(pass + ".jsonl");
        if (!Files.exists(file)) {
            return out;
        }
        for (String line : Files.readAllLines(file)) {
            try {
                Entry e = JSON.readValue(line, Entry.class);
                out.put(e.file(), e);
            } catch (IOException ignored) {
                // a line cut short by a crash: that file is simply not finished
            }
        }
        return out;
    }
}
