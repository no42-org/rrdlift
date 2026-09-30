/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift;

import org.no42.rrdlift.cli.BackfillCommand;
import org.no42.rrdlift.cli.PlanCommand;
import org.no42.rrdlift.cli.PreflightCommand;
import org.no42.rrdlift.cli.SnapshotLabelsCommand;
import org.no42.rrdlift.cli.VerifyCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(
        name = "rrdlift",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Migrates OpenNMS RRDtool and JRobin history into Prometheus-compatible backends.",
        subcommands = {PreflightCommand.class, SnapshotLabelsCommand.class, PlanCommand.class,
                BackfillCommand.class, VerifyCommand.class})
public final class Main implements Runnable {

    /** sysexits.h EX_USAGE. Picocli's default of 2 would collide with backfill's paused code. */
    static final int EX_USAGE = 64;

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    public static int run(String... args) {
        CommandLine cli = new CommandLine(new Main()).setCaseInsensitiveEnumValuesAllowed(true);
        cli.setParameterExceptionHandler((ex, rawArgs) -> {
            CommandLine c = ex.getCommandLine();
            c.getErr().println(ex.getMessage());
            if (!CommandLine.UnmatchedArgumentException.printSuggestions(ex, c.getErr())) {
                ex.getCommandLine().usage(c.getErr());
            }
            return EX_USAGE;
        });
        return cli.execute(args);
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }
}
