/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.repo;

import java.nio.file.Path;
import java.util.List;

public record WalkResult(List<WorkItem> items, List<Skipped> skipped) {

    public record Skipped(Path file, String reason) {}
}
