/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.rrd;

import java.util.List;

/** Everything rrdlift needs from one RRDtool or JRobin file. Times are epoch seconds. */
public record RrdFile(String version, long step, long lastUpdate,
                      List<DataSource> dataSources, List<Archive> archives) {}
