/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.repo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Finds RRD files under share/rrd/snmp and share/rrd/response and derives their resourceId. */
public final class RepositoryWalker {

    private static final List<String> ROOTS = List.of("snmp", "response");

    private RepositoryWalker() {}

    public static WalkResult walk(Path rrdDir) throws IOException {
        List<WorkItem> items = new ArrayList<>();
        List<WalkResult.Skipped> skipped = new ArrayList<>();
        for (String root : ROOTS) {
            Path top = rrdDir.resolve(root);
            if (!Files.isDirectory(top)) {
                continue;
            }
            Files.walkFileTree(top, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                            walkDirectory(rrdDir, dir, items, skipped);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException e) {
                            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                            skipped.add(new WalkResult.Skipped(file, "cannot read: " + message));
                            return FileVisitResult.CONTINUE;
                        }
                    });
        }
        items.sort(Comparator.comparing(WorkItem::file));
        skipped.sort(Comparator.comparing(WalkResult.Skipped::file));
        return new WalkResult(List.copyOf(items), List.copyOf(skipped));
    }

    private static void walkDirectory(Path rrdDir, Path dir, List<WorkItem> items,
                                      List<WalkResult.Skipped> skipped) throws IOException {
        String relative = relative(rrdDir, dir);
        Set<Path> referenced = new HashSet<>();
        Path ds = dir.resolve("ds.properties");
        if (Files.isRegularFile(ds)) {
            Properties dsProps = load(ds, items, skipped);
            if (dsProps != null) {
                for (String group : new TreeSet<>(dsProps.stringPropertyNames().stream()
                        .map(k -> dsProps.getProperty(k)).collect(Collectors.toSet()))) {
                    Path file = pick(dir, group, referenced, skipped);
                    if (file == null) {
                        skipped.add(new WalkResult.Skipped(dir.resolve(group), "listed in ds.properties but no .rrd or .jrb file"));
                    } else {
                        items.add(new WorkItem(file, relative + "/" + group, group));
                    }
                }
            }
        } else {
            List<Path> metas;
            try (Stream<Path> s = Files.list(dir)) {
                metas = s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".meta")).sorted().collect(Collectors.toList());
            }
            for (Path meta : metas) {
                Properties metaProps = load(meta, items, skipped);
                if (metaProps == null) {
                    continue;
                }
                String group = metaProps.getProperty("GROUP");
                if (group == null) {
                    continue;
                }
                String metric = meta.getFileName().toString().replaceFirst("\\.meta$", "");
                Path file = pick(dir, metric, referenced, skipped);
                if (file == null) {
                    skipped.add(new WalkResult.Skipped(dir.resolve(metric), "listed in .meta but no .rrd or .jrb file"));
                } else {
                    items.add(new WorkItem(file, relative + "/" + group, group));
                }
            }
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> isRrdFile(p) && !referenced.contains(p)).sorted()
                    .forEach(p -> skipped.add(new WalkResult.Skipped(p, "no group information")));
        }
    }

    /** Resolves {@code base.rrd} or {@code base.jrb}; marks every candidate as referenced. */
    private static Path pick(Path dir, String base, Set<Path> referenced, List<WalkResult.Skipped> skipped)
            throws IOException {
        Path rrd = dir.resolve(base + ".rrd");
        Path jrb = dir.resolve(base + ".jrb");
        boolean hasRrd = Files.isRegularFile(rrd);
        boolean hasJrb = Files.isRegularFile(jrb);
        if (hasRrd) {
            referenced.add(rrd);
        }
        if (hasJrb) {
            referenced.add(jrb);
        }
        if (hasRrd && hasJrb) {
            boolean jrbNewer = Files.getLastModifiedTime(jrb).compareTo(Files.getLastModifiedTime(rrd)) > 0;
            Path keep = jrbNewer ? jrb : rrd;
            Path drop = jrbNewer ? rrd : jrb;
            skipped.add(new WalkResult.Skipped(drop, "older duplicate of " + keep.getFileName()));
            return keep;
        }
        return hasRrd ? rrd : hasJrb ? jrb : null;
    }

    public static String nodePrefix(String resourceId) {
        String[] parts = resourceId.split("/");
        int n = parts.length >= 4 && parts[0].equals("snmp") && parts[1].equals("fs") ? 4 : 2;
        return String.join("/", java.util.Arrays.copyOf(parts, Math.min(n, parts.length)));
    }

    private static boolean isRrdFile(Path p) {
        String name = p.getFileName().toString();
        return Files.isRegularFile(p) && (name.endsWith(".rrd") || name.endsWith(".jrb"));
    }

    private static String relative(Path rrdDir, Path dir) {
        List<String> parts = new ArrayList<>();
        rrdDir.relativize(dir).forEach(p -> parts.add(p.toString()));
        return String.join("/", parts);
    }

    private static Properties load(Path file, List<WorkItem> items, List<WalkResult.Skipped> skipped) {
        Properties p = new Properties();
        try (InputStream is = Files.newInputStream(file)) {
            p.load(is);
            return p;
        } catch (IOException e) {
            skipped.add(new WalkResult.Skipped(file, "cannot read: " + e.getMessage()));
            return null;
        }
    }
}
