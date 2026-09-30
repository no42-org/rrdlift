/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.labels;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Label sets by series key. More than one set for a key means the key is ambiguous. */
public final class LabelIndex {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Map<SeriesKey, List<Map<String, String>>> byKey = new LinkedHashMap<>();

    public synchronized void add(Map<String, String> labels) {
        SeriesKey key = SeriesKey.of(labels);
        if (key == null) {
            return;
        }
        List<Map<String, String>> sets = byKey.computeIfAbsent(key, k -> new ArrayList<>());
        Map<String, String> sorted = new TreeMap<>(labels);
        if (!sets.contains(sorted)) {
            sets.add(sorted);
        }
    }

    public synchronized List<Map<String, String>> lookup(SeriesKey key) {
        return List.copyOf(byKey.getOrDefault(key, List.of()));
    }

    public synchronized int keys() {
        return byKey.size();
    }

    public synchronized int labelSets() {
        return byKey.values().stream().mapToInt(List::size).sum();
    }

    public synchronized List<Map<String, String>> all() {
        List<Map<String, String>> out = new ArrayList<>();
        byKey.values().forEach(out::addAll);
        return out;
    }

    public synchronized void save(Path file) throws IOException {
        List<Map<String, String>> all = new ArrayList<>();
        byKey.values().forEach(all::addAll);
        JSON.writeValue(file.toFile(), all);
    }

    public static LabelIndex load(Path file) throws IOException {
        List<Map<String, String>> all = JSON.readValue(file.toFile(), new TypeReference<>() {});
        LabelIndex index = new LabelIndex();
        all.forEach(index::add);
        return index;
    }
}
