/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.plan;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import org.no42.rrdlift.labels.LabelIndex;
import org.no42.rrdlift.labels.Sanitizers;
import org.no42.rrdlift.opennms.MetaTagConfig;
import org.no42.rrdlift.opennms.NodeExpressions;
import org.no42.rrdlift.opennms.OpennmsClient;
import org.no42.rrdlift.opennms.RrdLabels;
import org.no42.rrdlift.opennms.TagScope;
import org.no42.rrdlift.repo.RepositoryWalker;

/** Builds the label set of a data source without a live series: siblings first, then REST for node scope. */
public final class LabelResolver {

    public record Result(Map<String, String> labels, Map<String, String> sources, List<String> unresolved,
                         boolean nodeGone) {}

    /** node null and gone false means unknown (REST off, or the node cannot be identified). */
    private record NodeLookup(OpennmsClient.NodeInfo node, boolean gone) {}

    private final MetaTagConfig config;
    private final SiblingIndex siblings;
    private final OpennmsClient rest;
    private final Map<String, NodeLookup> nodes = new HashMap<>();
    private final Map<Integer, List<OpennmsClient.SnmpInterface>> snmp = new HashMap<>();

    public LabelResolver(MetaTagConfig config, LabelIndex live, OpennmsClient rest) {
        this.config = config;
        this.siblings = new SiblingIndex(live);
        this.rest = rest;
    }

    public Result resolve(String resourceId, String dsName, String mtype) throws IOException {
        Map<String, String> labels = Planner.minimalLabels(resourceId, dsName, mtype);
        Map<String, String> sources = new TreeMap<>();
        labels.keySet().forEach(k -> sources.put(k, "minimal"));
        List<String> unresolved = new ArrayList<>();
        if (config == null) {
            unresolved.add("meta-tag config not given (--opennms-home)");
            return new Result(labels, sources, unresolved, false);
        }
        boolean gone = false;
        for (Map.Entry<String, String> tag : config.tags().entrySet()) {
            String name = Sanitizers.sanitizeLabelName(tag.getKey());
            String expression = tag.getValue();
            TagScope scope = MetaTagConfig.scopeOf(expression);
            if (scope == TagScope.CONSTANT) {
                put(labels, sources, name, expression, "constant");
                continue;
            }
            if (scope == TagScope.RESOURCE && expression.trim().equals("${resource:label}")) {
                Optional<String> label = interfaceLabel(resourceId);
                if (label.isPresent()) {
                    put(labels, sources, name, label.get(), "derived:resource-dir");
                    continue;
                }
            }
            SiblingIndex.Outcome o = siblings.lookup(name, scope, resourceId);
            if (o.kind() == SiblingIndex.Kind.VALUE) {
                put(labels, sources, name, o.value(), "sibling:" + o.source());
                continue;
            }
            if (o.kind() == SiblingIndex.Kind.EMPTY) {
                continue;
            }
            if (scope == TagScope.NODE) {
                NodeLookup n = lookupNode(resourceId);
                if (n.gone()) {
                    gone = true;
                    continue;
                }
                if (n.node() != null) {
                    Optional<String> v = NodeExpressions.eval(expression, n.node());
                    if (v.isPresent()) {
                        put(labels, sources, name, v.get(), "rest:node/" + n.node().id());
                        continue;
                    }
                }
            }
            unresolved.add(name);
        }
        if (config.exposeCategories()) {
            Optional<SiblingIndex.Categories> cats = siblings.categories(resourceId);
            if (cats.isPresent()) {
                cats.get().values().forEach((k, v) -> put(labels, sources, k, v, "sibling:" + cats.get().source()));
            } else {
                NodeLookup n = lookupNode(resourceId);
                if (n.gone()) {
                    gone = true;
                } else if (n.node() != null) {
                    TreeSet<String> names = new TreeSet<>(n.node().categories());
                    for (String c : names) {
                        put(labels, sources, Sanitizers.sanitizeLabelName("cat_" + c), c, "rest:node/" + n.node().id());
                    }
                    if (!names.isEmpty()) {
                        put(labels, sources, "categories", String.join(",", names), "rest:node/" + n.node().id());
                    }
                } else {
                    unresolved.add("categories");
                }
            }
        }
        if (gone) {
            Map<String, String> minimal = Planner.minimalLabels(resourceId, dsName, mtype);
            Map<String, String> minimalSources = new TreeMap<>();
            minimal.keySet().forEach(k -> minimalSources.put(k, "minimal"));
            return new Result(minimal, minimalSources, List.of(), true);
        }
        return new Result(labels, sources, unresolved, false);
    }

    /** The node behind a resource, via REST. Empty when REST is off, the node is gone or cannot be identified. */
    public Optional<OpennmsClient.NodeInfo> node(String resourceId) throws IOException {
        return Optional.ofNullable(lookupNode(resourceId).node());
    }

    /** True when REST lists the resource's SNMP interface with data collection enabled. */
    public boolean scheduledForCollection(String resourceId) throws IOException {
        if (rest == null || SiblingIndex.isAliased(resourceId)) {
            return false;
        }
        Optional<String> label = interfaceLabel(resourceId);
        Optional<OpennmsClient.NodeInfo> node = label.isEmpty() ? Optional.empty() : node(resourceId);
        if (node.isEmpty()) {
            return false;
        }
        List<OpennmsClient.SnmpInterface> ifs = snmp.get(node.get().id());
        if (ifs == null) {
            ifs = rest.snmpInterfaces(node.get().id());
            snmp.put(node.get().id(), ifs);
        }
        boolean prefer = config != null && config.preferIfDescr();
        boolean dont = config != null && config.dontSanitizeIfName();
        for (OpennmsClient.SnmpInterface i : ifs) {
            if (i.collect() && label.get().equals(RrdLabels.label(i.ifName(), i.ifDescr(), i.physAddr(), prefer, dont))) {
                return true;
            }
        }
        return false;
    }

    private NodeLookup lookupNode(String resourceId) throws IOException {
        if (rest == null) {
            return new NodeLookup(null, false);
        }
        String key = RepositoryWalker.nodePrefix(resourceId);
        NodeLookup cached = nodes.get(key);
        if (cached != null) {
            return cached;
        }
        String[] p = resourceId.split("/");
        NodeLookup result;
        if (p[0].equals("response") && p.length >= 2) {
            List<OpennmsClient.NodeInfo> found = rest.nodesByIp(p[1]);
            result = found.size() == 1 ? new NodeLookup(found.get(0), false)
                    : new NodeLookup(null, found.isEmpty());
        } else {
            Optional<String> criteria = nodeCriteria(resourceId);
            if (criteria.isEmpty()) {
                result = new NodeLookup(null, false);
            } else {
                Optional<OpennmsClient.NodeInfo> n = rest.node(criteria.get());
                result = n.map(info -> new NodeLookup(info, false)).orElseGet(() -> new NodeLookup(null, true));
            }
        }
        nodes.put(key, result);
        return result;
    }

    static Optional<String> nodeCriteria(String resourceId) {
        String[] p = resourceId.split("/");
        if (p.length >= 4 && p[0].equals("snmp") && p[1].equals("fs")) {
            return Optional.of(p[2] + ":" + p[3]);
        }
        if (p.length >= 2 && p[0].equals("snmp") && p[1].chars().allMatch(Character::isDigit)) {
            return Optional.of(p[1]);
        }
        return Optional.empty();
    }

    /**
     * ${resource:label} for interface resources: the interface directory name, or domain/alias for
     * storeByIfAlias directories. Empty for node-level and response resources.
     */
    public static Optional<String> interfaceLabel(String resourceId) {
        String[] p = resourceId.split("/");
        if (!p[0].equals("snmp")) {
            return Optional.empty();
        }
        int nodeDepth = RepositoryWalker.nodePrefix(resourceId).split("/").length;
        if (p.length - 1 <= nodeDepth) {
            return Optional.empty(); // node-level resource: snmp/<node>/<group>
        }
        return Optional.of(SiblingIndex.isAliased(resourceId) ? p[1] + "/" + p[2] : p[p.length - 2]);
    }

    private static void put(Map<String, String> labels, Map<String, String> sources, String name, String value,
                            String source) {
        if (value == null || value.isEmpty()) {
            return;
        }
        labels.put(name, Sanitizers.sanitizeLabelValue(value));
        sources.put(name, source);
    }
}
