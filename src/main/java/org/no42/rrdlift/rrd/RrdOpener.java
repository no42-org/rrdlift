/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.rrd;

import java.io.IOException;
import java.io.InputStream;
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
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        RrdFile rrd;
        try (InputStream out = process.getInputStream()) {
            rrd = DumpXmlParser.parse(out);
        }
        try {
            if (process.waitFor() != 0) {
                throw new IOException(file + ": rrdtool dump exited with " + process.exitValue());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while running rrdtool dump", e);
        }
        return rrd;
    }
}
