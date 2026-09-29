/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Derived from the OpenNMS Prometheus remote-write plugin (CortexTSS), Copyright The OpenNMS Group, Inc.
 */
package org.no42.rrdlift.labels;

/** Copies of the plugin's sanitizers. Keep byte-identical; SanitizersTest pins them. */
public final class Sanitizers {

    private Sanitizers() {}

    public static String sanitizeMetricName(String metricName) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < metricName.length(); i++) {
            char b = metricName.charAt(i);
            if (!((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || b == '_' || b == ':'
                    || (b >= '0' && b <= '9' && i > 0))) {
                sb.append("_");
            } else {
                sb.append(b);
            }
        }
        return sb.toString();
    }

    public static String sanitizeLabelName(String labelName) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < labelName.length(); i++) {
            char b = labelName.charAt(i);
            if (!((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || b == '_'
                    || (b >= '0' && b <= '9' && i > 0))) {
                sb.append("_");
            } else {
                sb.append(b);
            }
        }
        return sb.toString();
    }

    public static String sanitizeLabelValue(String labelValue) {
        return labelValue.substring(0, Math.min(labelValue.length(), 2048));
    }
}
