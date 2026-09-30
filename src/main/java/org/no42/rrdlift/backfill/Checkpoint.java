/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.backfill;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/** Per-pass progress: one JSON line per finished file; the last line for a file wins. */
public final class Checkpoint {

    public enum Status { DONE, FAILED }

    /** labelHash is null in lines written by rrdlift 0.1. */
    public record Entry(String file, String pass, Status status, long samples, int attempts, String error,
                        String labelHash) {

        public Entry(String file, String pass, Status status, long samples, int attempts, String error) {
            this(file, pass, status, samples, attempts, error, null);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path dir;

    public Checkpoint(Path stateDir) {
        this.dir = stateDir.resolve("state");
    }

    public synchronized void append(Entry e) throws IOException {
        Files.createDirectories(dir);
        Path path = dir.resolve(e.pass() + ".jsonl");

        // If file exists, is non-empty, and doesn't end with '\n', write '\n' first
        if (Files.exists(path)) {
            long size = Files.size(path);
            if (size > 0) {
                try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
                    ByteBuffer buf = ByteBuffer.allocate(1);
                    ch.read(buf, size - 1);
                    byte lastByte = buf.get(0);
                    if (lastByte != '\n') {
                        try (FileChannel appendCh = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                            appendCh.write(ByteBuffer.wrap(new byte[] {'\n'}));
                        }
                    }
                }
            }
        }

        byte[] line = (JSON.writeValueAsString(e) + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(path,
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

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);

        try (FileInputStream fis = new FileInputStream(file.toFile());
             InputStreamReader isr = new InputStreamReader(fis, decoder);
             BufferedReader br = new BufferedReader(isr)) {
            String line;
            while ((line = br.readLine()) != null) {
                try {
                    Entry e = JSON.readValue(line, Entry.class);
                    if (e != null && e.file() != null && e.status() != null) {
                        out.put(e.file(), e);
                    }
                } catch (IOException ignored) {
                    // a line cut short by a crash: that file is simply not finished
                }
            }
        }
        return out;
    }
}
