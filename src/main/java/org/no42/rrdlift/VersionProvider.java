/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import picocli.CommandLine.IVersionProvider;

public final class VersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() throws IOException {
        Properties p = new Properties();
        try (InputStream in = VersionProvider.class.getResourceAsStream("/rrdlift.properties")) {
            p.load(in);
        }
        return new String[] {"rrdlift " + p.getProperty("version")};
    }
}
