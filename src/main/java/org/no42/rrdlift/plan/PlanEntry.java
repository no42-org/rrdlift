/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.plan;

import java.util.List;
import java.util.Map;

/**
 * Decision for one data source. {@code labels} is non-null exactly when the data source will be written.
 * {@code labelSources} maps label name to where its value came from; {@code candidates} holds the competing label
 * sets of an AMBIGUOUS entry.
 */
public record PlanEntry(String file, String resourceId, String dsName, String mtype, EntryClass entryClass,
                        Map<String, String> labels, long samples, String note,
                        Map<String, String> labelSources, List<Map<String, String>> candidates) {

    public PlanEntry(String file, String resourceId, String dsName, String mtype, EntryClass entryClass,
                     Map<String, String> labels, long samples, String note) {
        this(file, resourceId, dsName, mtype, entryClass, labels, samples, note, null, null);
    }
}
