/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.opennms;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.no42.rrdlift.labels.Sanitizers;

/** The meta tags the Prometheus remote-write plugin adds to every series, as configured in OpenNMS. */
public record MetaTagConfig(Map<String, String> tags, boolean exposeCategories, boolean preferIfDescr,
                            boolean dontSanitizeIfName) {

    static final String PREFIX = "org.opennms.timeseries.tin.metatags.";

    /** Reads etc/opennms.properties, then etc/opennms.properties.d/*.properties sorted by name; later values win. */
    public static MetaTagConfig load(Path opennmsHome) throws IOException {
        Path etc = opennmsHome.resolve("etc");
        List<Path> files = new ArrayList<>();
        if (Files.isRegularFile(etc.resolve("opennms.properties"))) {
            files.add(etc.resolve("opennms.properties"));
        }
        Path dotD = etc.resolve("opennms.properties.d");
        if (Files.isDirectory(dotD)) {
            try (Stream<Path> s = Files.list(dotD)) {
                s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".properties"))
                        .sorted().forEach(files::add);
            }
        }
        Map<String, String> props = new HashMap<>();
        for (Path f : files) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(f)) {
                p.load(in);
            }
            p.stringPropertyNames().forEach(k -> props.put(k, p.getProperty(k)));
        }
        return fromProperties(props);
    }

    public static MetaTagConfig fromProperties(Map<String, String> props) {
        Map<String, String> tags = new TreeMap<>();
        props.forEach((k, v) -> {
            if (k.startsWith(PREFIX + "tag.")) {
                tags.put(k.substring((PREFIX + "tag.").length()), v);
            }
        });
        return new MetaTagConfig(tags,
                Boolean.parseBoolean(props.get(PREFIX + "exposeCategories")),
                Boolean.parseBoolean(props.get("org.opennms.core.utils.preferIfDescr")),
                Boolean.parseBoolean(props.get("org.opennms.core.utils.dontSanitizeIfName")));
    }

    public static TagScope scopeOf(String expression) {
        if (expression == null) {
            return TagScope.UNCLASSIFIED;
        }
        TagScope result = null;
        boolean anyToken = false;
        int i = 0;
        while ((i = expression.indexOf("${", i)) >= 0) {
            int end = expression.indexOf('}', i + 2);
            if (end < 0) {
                return TagScope.UNCLASSIFIED;
            }
            anyToken = true;
            for (String alternative : expression.substring(i + 2, end).split("\\|")) {
                int colon = alternative.indexOf(':');
                if (colon <= 0) {
                    continue; // literal default value
                }
                TagScope s = contextScope(alternative.substring(0, colon).trim());
                if (result == null || s.ordinal() > result.ordinal()) {
                    result = s;
                }
            }
            i = end + 1;
        }
        if (!anyToken) {
            return TagScope.CONSTANT;
        }
        return result == null ? TagScope.UNCLASSIFIED : result;
    }

    private static TagScope contextScope(String context) {
        return switch (context) {
            case "node", "asset" -> TagScope.NODE;
            case "interface" -> TagScope.INTERFACE;
            case "service" -> TagScope.SERVICE;
            case "resource" -> TagScope.RESOURCE;
            default -> TagScope.METADATA;
        };
    }

    /** Tag key to scope, sorted by key. */
    public Map<String, TagScope> scopes() {
        Map<String, TagScope> out = new TreeMap<>();
        tags.forEach((k, v) -> out.put(k, scopeOf(v)));
        return out;
    }

    /** SHA-256 over the sorted tag definitions and exposeCategories, as 64 hex characters. */
    public String hash() {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(tags).forEach((k, v) -> sb.append("tag.").append(k).append('=').append(v).append('\n'));
        sb.append("exposeCategories=").append(exposeCategories).append('\n');
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Label names every series is expected to carry beyond the intrinsic ones; cat_* names are per node. */
    public Set<String> expectedKeys() {
        Set<String> keys = new TreeSet<>();
        tags.keySet().forEach(k -> keys.add(Sanitizers.sanitizeLabelName(k)));
        if (exposeCategories) {
            keys.add("categories");
        }
        return keys;
    }
}
