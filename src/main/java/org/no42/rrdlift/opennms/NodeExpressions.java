/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.opennms;

import java.util.Optional;

/**
 * Evaluates a node-scope meta-tag expression against REST node data, with OpenNMS fallback semantics:
 * alternatives separated by '|' are tried left to right, an alternative without ':' is a literal default.
 */
public final class NodeExpressions {

    private NodeExpressions() {}

    /** Empty Optional when the expression is malformed or uses an attribute this evaluator does not support. */
    public static Optional<String> eval(String expression, OpennmsClient.NodeInfo node) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (true) {
            int start = expression.indexOf("${", i);
            if (start < 0) {
                out.append(expression.substring(i));
                return Optional.of(out.toString());
            }
            out.append(expression, i, start);
            int end = expression.indexOf('}', start + 2);
            if (end < 0) {
                return Optional.empty();
            }
            String value = null;
            for (String alternative : expression.substring(start + 2, end).split("\\|")) {
                int colon = alternative.indexOf(':');
                if (colon <= 0) {
                    value = alternative;
                    break;
                }
                Optional<String> v = attribute(alternative.substring(0, colon).trim(),
                        alternative.substring(colon + 1).trim(), node);
                if (v == null) {
                    return Optional.empty();
                }
                if (v.isPresent() && !v.get().isEmpty()) {
                    value = v.get();
                    break;
                }
            }
            out.append(value == null ? "" : value);
            i = end + 1;
        }
    }

    /** null when unsupported; empty Optional when supported but without a value. */
    private static Optional<String> attribute(String context, String key, OpennmsClient.NodeInfo n) {
        if (context.equals("node")) {
            return switch (key) {
                case "label" -> Optional.ofNullable(n.label());
                case "location" -> Optional.ofNullable(n.location());
                case "foreign-source" -> Optional.ofNullable(n.foreignSource());
                case "foreign-id" -> Optional.ofNullable(n.foreignId());
                case "criteria" -> Optional.of(n.foreignSource() != null && n.foreignId() != null
                        ? n.foreignSource() + ":" + n.foreignId() : String.valueOf(n.id()));
                default -> null;
            };
        }
        if (context.equals("asset")) {
            return Optional.ofNullable(n.assets().get(camel(key)));
        }
        return null;
    }

    private static String camel(String kebab) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : kebab.toCharArray()) {
            if (c == '-') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }
}
