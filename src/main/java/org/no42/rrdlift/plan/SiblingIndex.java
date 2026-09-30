/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.no42.rrdlift.labels.LabelIndex;
import org.no42.rrdlift.opennms.TagScope;
import org.no42.rrdlift.repo.RepositoryWalker;

/** Live label sets grouped by resourceId, resource directory and node directory. */
final class SiblingIndex {

    enum Kind { VALUE, EMPTY, CONFLICT, NONE }

    record Outcome(Kind kind, String value, String source) {}

    record Categories(Map<String, String> values, String source) {}

    private final Map<String, List<Map<String, String>>> byResource = new HashMap<>();
    private final Map<String, List<Map<String, String>>> byDir = new HashMap<>();
    private final Map<String, List<Map<String, String>>> byNode = new HashMap<>();
    private final Map<List<Map<String, String>>, Map<String, Outcome>> agreed = new IdentityHashMap<>();
    private final Map<List<Map<String, String>>, Optional<Categories>> categoryCache = new IdentityHashMap<>();

    SiblingIndex(LabelIndex live) {
        for (Map<String, String> labels : live.all()) {
            String rid = labels.get("resourceId");
            if (rid == null || rid.indexOf('/') < 0) {
                continue;
            }
            byResource.computeIfAbsent(rid, k -> new ArrayList<>()).add(labels);
            byDir.computeIfAbsent(dirOf(rid), k -> new ArrayList<>()).add(labels);
            byNode.computeIfAbsent(RepositoryWalker.nodePrefix(rid), k -> new ArrayList<>()).add(labels);
        }
    }

    Outcome lookup(String name, TagScope scope, String resourceId) {
        List<List<Map<String, String>>> levels = switch (scope) {
            case NODE -> nodeLevels(resourceId);
            case INTERFACE -> List.of(get(byResource, resourceId), get(byDir, dirOf(resourceId)));
            case METADATA -> isResponse(resourceId)
                    ? List.of(get(byResource, resourceId))
                    : List.of(get(byResource, resourceId), get(byDir, dirOf(resourceId)));
            case SERVICE, RESOURCE, UNCLASSIFIED -> List.of(get(byResource, resourceId));
            case CONSTANT -> List.of();
        };
        for (List<Map<String, String>> level : levels) {
            if (!level.isEmpty()) {
                return agreed.computeIfAbsent(level, k -> new HashMap<>())
                        .computeIfAbsent(name, n -> agree(level, n));
            }
        }
        return new Outcome(Kind.NONE, null, null);
    }

    /** The cat_* and categories labels shared by every live series of the node, if they agree. */
    Optional<Categories> categories(String resourceId) {
        List<List<Map<String, String>>> levels = nodeLevels(resourceId);
        for (List<Map<String, String>> level : levels) {
            if (!level.isEmpty()) {
                return categoryCache.computeIfAbsent(level, SiblingIndex::categoriesOf);
            }
        }
        return Optional.empty();
    }

    /**
     * Where node-level values may be copied from. Interfaces under a storeByIfAlias domain directory
     * (snmp/&lt;domain&gt;/&lt;alias&gt;) belong to different nodes, so only the same resource or alias directory counts.
     */
    private List<List<Map<String, String>>> nodeLevels(String resourceId) {
        return isAliased(resourceId)
                ? List.of(get(byResource, resourceId), get(byDir, dirOf(resourceId)))
                : List.of(get(byNode, RepositoryWalker.nodePrefix(resourceId)));
    }

    /** True for snmp/&lt;domain&gt;/&lt;alias&gt;/..., whose first level below snmp is neither a node id nor fs. */
    static boolean isAliased(String resourceId) {
        String[] p = resourceId.split("/");
        return p.length >= 2 && p[0].equals("snmp") && !p[1].equals("fs")
                && !p[1].chars().allMatch(Character::isDigit);
    }

    private static boolean isResponse(String resourceId) {
        return resourceId.startsWith("response/");
    }

    private static Optional<Categories> categoriesOf(List<Map<String, String>> candidates) {
        Set<Map<String, String>> distinct = new HashSet<>();
        for (Map<String, String> c : candidates) {
            Map<String, String> cats = new TreeMap<>();
            c.forEach((k, v) -> {
                if (k.startsWith("cat_") || k.equals("categories")) {
                    cats.put(k, v);
                }
            });
            distinct.add(cats);
        }
        return distinct.size() == 1
                ? Optional.of(new Categories(distinct.iterator().next(), candidates.get(0).get("resourceId")))
                : Optional.empty();
    }

    private static Outcome agree(List<Map<String, String>> candidates, String name) {
        Set<String> values = new HashSet<>();
        boolean absent = false;
        String source = null;
        for (Map<String, String> c : candidates) {
            String v = c.get(name);
            if (v == null) {
                absent = true;
            } else {
                values.add(v);
                if (source == null) {
                    source = c.get("resourceId");
                }
            }
        }
        if (values.isEmpty()) {
            return new Outcome(Kind.EMPTY, null, candidates.get(0).get("resourceId"));
        }
        if (values.size() == 1 && !absent) {
            return new Outcome(Kind.VALUE, values.iterator().next(), source);
        }
        return new Outcome(Kind.CONFLICT, null, null);
    }

    private static List<Map<String, String>> get(Map<String, List<Map<String, String>>> m, String key) {
        return m.getOrDefault(key, List.of());
    }

    static String dirOf(String resourceId) {
        return resourceId.substring(0, resourceId.lastIndexOf('/'));
    }
}
