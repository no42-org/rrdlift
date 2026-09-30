/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.labels;

import java.util.Map;

/** Identifies a series by the two intrinsic OpenNMS tags as the plugin writes them. */
public record SeriesKey(String resourceId, String metricName) {

    public static SeriesKey of(Map<String, String> labels) {
        String resourceId = labels.get("resourceId");
        String name = labels.get("__name__");
        return resourceId == null || name == null ? null : new SeriesKey(resourceId, name);
    }

    public static SeriesKey forDataSource(String resourceId, String dsName) {
        return new SeriesKey(Sanitizers.sanitizeLabelValue(resourceId), Sanitizers.sanitizeMetricName(dsName));
    }
}
