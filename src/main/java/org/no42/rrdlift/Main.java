/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift;

import org.no42.rrdlift.cli.PlanCommand;
import org.no42.rrdlift.cli.SnapshotLabelsCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(
        name = "rrdlift",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Migrates OpenNMS RRDtool and JRobin history into Prometheus-compatible backends.",
        subcommands = {SnapshotLabelsCommand.class, PlanCommand.class})
public final class Main implements Runnable {

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    public static int run(String... args) {
        return new CommandLine(new Main()).setCaseInsensitiveEnumValuesAllowed(true).execute(args);
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }
}
