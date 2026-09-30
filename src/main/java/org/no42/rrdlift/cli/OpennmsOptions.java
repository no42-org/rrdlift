/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.cli;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.no42.rrdlift.opennms.OpennmsClient;
import picocli.CommandLine.Option;

/** OpenNMS REST access; the password never appears on the command line. */
public final class OpennmsOptions {

    @Option(names = "--opennms-url", description = "OpenNMS web root, e.g. http://opennms:8980/opennms")
    URI url;

    @Option(names = "--opennms-user", defaultValue = "admin")
    String user;

    @Option(names = "--opennms-password-file", description = "File holding the OpenNMS password (else RRDLIFT_OPENNMS_PASSWORD)")
    Path passwordFile;

    @Option(names = "--no-rest", description = "Do not call the OpenNMS REST API")
    boolean noRest;

    /** null when REST is disabled or no URL is given. */
    public OpennmsClient client() throws IOException {
        if (noRest || url == null) {
            return null;
        }
        String password = passwordFile != null ? Files.readString(passwordFile).strip()
                : System.getenv("RRDLIFT_OPENNMS_PASSWORD");
        if (password == null) {
            throw new IOException("--opennms-url needs --opennms-password-file or RRDLIFT_OPENNMS_PASSWORD");
        }
        if (password.isBlank()) {
            throw new IOException("empty OpenNMS password (--opennms-password-file or RRDLIFT_OPENNMS_PASSWORD)");
        }
        OpennmsClient client = new OpennmsClient(url, user, password);
        client.ping();
        return client;
    }
}
