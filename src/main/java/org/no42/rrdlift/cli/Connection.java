/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.cli;

import java.net.URI;
import org.no42.rrdlift.prom.PromClient;
import picocli.CommandLine.Option;

/** Backend connection options shared by the commands. */
public final class Connection {

    @Option(names = "--write-url", description = "Remote-write endpoint, e.g. http://prometheus:9090/api/v1/write")
    URI writeUrl;

    @Option(names = "--read-url", description = "Prometheus HTTP API base, e.g. http://prometheus:9090")
    URI readUrl;

    @Option(names = "--org-id", description = "Tenant sent as X-Scope-OrgID (Cortex, Mimir)")
    String orgId;

    public PromClient client() {
        return new PromClient(writeUrl, readUrl, orgId);
    }
}
