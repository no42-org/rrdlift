/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.repo;

import java.nio.file.Path;

/** One RRD file and the TSS resourceId its data sources belong to. */
public record WorkItem(Path file, String resourceId, String group) {}
