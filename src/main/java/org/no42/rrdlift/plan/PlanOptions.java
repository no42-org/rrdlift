/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.plan;

import org.no42.rrdlift.opennms.MetaTagConfig;
import org.no42.rrdlift.opennms.OpennmsClient;

/**
 * Planner switches; config and rest may be null.
 * snapshotCutoverSec is the cutover the label snapshot recorded, or 0 when there is none: the planner then uses
 * the newest lastUpdate it reads itself.
 */
public record PlanOptions(boolean skipOrphans, boolean skipPartial, boolean pending, MetaTagConfig config,
                          OpennmsClient rest, long snapshotCutoverSec) {

    public PlanOptions(boolean skipOrphans, boolean skipPartial, boolean pending, MetaTagConfig config,
                       OpennmsClient rest) {
        this(skipOrphans, skipPartial, pending, config, rest, 0);
    }
}
