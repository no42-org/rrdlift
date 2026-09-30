/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.plan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

/** Fingerprint of the label sets a file's entries are written with. */
public final class LabelHash {

    private LabelHash() {}

    public static String of(List<PlanEntry> entries) {
        StringBuilder sb = new StringBuilder();
        entries.stream().sorted(Comparator.comparing(PlanEntry::dsName)).forEach(e -> sb.append(e.dsName()).append('\n')
                .append(e.labels() == null ? "null" : new TreeMap<>(e.labels()).toString()).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
