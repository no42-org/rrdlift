/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.cli;

import java.nio.file.Path;
import org.no42.rrdlift.rrd.RrdOpener;
import picocli.CommandLine.Option;

/** How RRD files are read. */
public final class ReaderOptions {

    @Option(names = "--reader", defaultValue = "NATIVE",
            description = "native (default) or rrdtool (runs `rrdtool dump` for every RRDtool file)")
    RrdOpener.Mode reader;

    @Option(names = "--rrdtool", description = "rrdtool binary, used for --reader rrdtool and as fallback for unverified layouts")
    Path rrdtool;

    public RrdOpener opener() {
        return new RrdOpener(reader, rrdtool);
    }
}
