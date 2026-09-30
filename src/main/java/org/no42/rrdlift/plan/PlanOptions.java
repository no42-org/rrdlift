/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.plan;

import org.no42.rrdlift.opennms.MetaTagConfig;
import org.no42.rrdlift.opennms.OpennmsClient;

/** Planner switches; config and rest may be null. */
public record PlanOptions(boolean skipOrphans, boolean skipPartial, MetaTagConfig config, OpennmsClient rest) {}
