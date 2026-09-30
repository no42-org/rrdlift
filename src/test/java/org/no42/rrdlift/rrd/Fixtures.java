/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.rrd;

import java.nio.file.Path;
import java.util.List;

public final class Fixtures {
    public static final List<String> ARCHES = List.of("aarch64", "x86_64");
    public static final List<String> FILES = List.of("empty", "grp", "icmp", "longname");

    private Fixtures() {}

    public static Path path(String arch, String name) {
        return Path.of("src/test/resources/fixtures", arch, name);
    }
}
