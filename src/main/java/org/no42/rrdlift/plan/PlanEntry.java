/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.plan;

import java.util.Map;

/** Decision for one data source. {@code labels} is non-null exactly when the data source will be written. */
public record PlanEntry(String file, String resourceId, String dsName, String mtype, EntryClass entryClass,
                        Map<String, String> labels, long samples, String note) {}
