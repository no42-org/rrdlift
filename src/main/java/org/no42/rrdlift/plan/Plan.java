/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record Plan(String rrdDir, long createdAt, long oldestSampleSec, List<PlanEntry> entries) {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public void save(Path file) throws IOException {
        JSON.writeValue(file.toFile(), this);
    }

    public static Plan load(Path file) throws IOException {
        return JSON.readValue(file.toFile(), Plan.class);
    }

    public Map<String, List<PlanEntry>> writableByFile() {
        Map<String, List<PlanEntry>> out = new LinkedHashMap<>();
        for (PlanEntry e : entries) {
            if (e.labels() != null) {
                out.computeIfAbsent(e.file(), k -> new ArrayList<>()).add(e);
            }
        }
        return out;
    }
}
