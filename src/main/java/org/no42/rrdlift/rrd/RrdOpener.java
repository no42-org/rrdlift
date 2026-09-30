/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.rrd;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Picks a reader from the file's first bytes and falls back to `rrdtool dump` for unverified layouts. */
public final class RrdOpener {

    public enum Mode { NATIVE, RRDTOOL }

    private static final byte[] RRD_COOKIE = {'R', 'R', 'D', 0};

    private final Mode mode;
    private final Path rrdtool;

    public RrdOpener(Mode mode, Path rrdtoolBinary) {
        this.mode = mode;
        this.rrdtool = rrdtoolBinary;
    }

    public RrdFile open(Path file) throws IOException {
        if (!isRrdtoolFile(file)) {
            return JrobinReader.read(file);
        }
        if (mode == Mode.RRDTOOL) {
            return dump(file, "--reader rrdtool");
        }
        try {
            return NativeRrdtoolReader.read(file);
        } catch (UnsupportedLayoutException e) {
            return dump(file, e.getMessage());
        }
    }

    public static boolean isRrdtoolFile(Path file) throws IOException {
        byte[] head = new byte[4];
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(head, 0, 4) == 4 && Arrays.equals(head, RRD_COOKIE);
        }
    }

    private RrdFile dump(Path file, String reason) throws IOException {
        if (rrdtool == null) {
            throw new IOException(reason + "; pass --rrdtool <path> to read it with rrdtool dump");
        }
        Process process = new ProcessBuilder(rrdtool.toString(), "dump", file.toString())
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start();
        try {
            RrdFile rrd;
            try (InputStream out = process.getInputStream()) {
                rrd = DumpXmlParser.parse(out);
            } catch (IOException e) {
                // Parse failed; collect stderr and exit code before throwing
                String stderr = readStderr(process);
                int exitCode = waitForProcess(process);
                throw new IOException(file + ": rrdtool dump exited with " + exitCode
                        + (stderr.isEmpty() ? "" : ": " + stderr), e);
            }
            int exitCode = waitForProcess(process);
            if (exitCode != 0) {
                String stderr = readStderr(process);
                throw new IOException(file + ": rrdtool dump exited with " + exitCode
                        + (stderr.isEmpty() ? "" : ": " + stderr));
            }
            return rrd;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private int waitForProcess(Process process) throws IOException {
        try {
            process.waitFor();
            return process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while running rrdtool dump", e);
        }
    }

    private String readStderr(Process process) throws IOException {
        byte[] buffer = new byte[2048];
        try (InputStream err = process.getErrorStream()) {
            int bytesRead = err.readNBytes(buffer, 0, buffer.length);
            return new String(buffer, 0, bytesRead, StandardCharsets.UTF_8).trim();
        }
    }
}
