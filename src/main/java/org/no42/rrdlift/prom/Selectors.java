/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.prom;

import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/** Builds PromQL selectors with correct string and regex escaping. */
public final class Selectors {

    private static final String REGEX_META = "\\.+*?()|[]{}^$";

    private Selectors() {}

    public static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    public static String regexEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (REGEX_META.indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public static String exact(Map<String, String> labels) {
        StringJoiner j = new StringJoiner(",", "{", "}");
        new TreeMap<>(labels).forEach((k, v) -> j.add(k + "=" + quote(v)));
        return j.toString();
    }

    public static String resourcePrefix(String prefix) {
        return "{resourceId=~" + quote(regexEscape(prefix) + "/.+") + "}";
    }
}
