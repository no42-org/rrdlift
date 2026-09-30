/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.repo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.no42.rrdlift.rrd.Fixtures;

/**
 * Builds:
 * <pre>
 * snmp/1/eth0-0011/ds.properties         8 grp DS names -> mib2-interfaces
 * snmp/1/eth0-0011/mib2-interfaces.rrd   (grp.rrd)
 * snmp/1/eth0-0011/strings.properties
 * snmp/fs/fs1/fid1/ds.properties         x=node-empty
 * snmp/fs/fs1/fid1/node-empty.rrd        (empty.rrd)
 * response/10.0.0.1/ds.properties        icmp=icmp
 * response/10.0.0.1/icmp.meta            ICMP/10.0.0.1=icmp
 * response/10.0.0.1/icmp.rrd             (icmp.rrd)
 * </pre>
 */
public final class TestRepos {

    private TestRepos() {}

    public static Path create(Path root) throws IOException {
        Path eth0 = Files.createDirectories(root.resolve("snmp/1/eth0-0011"));
        Files.writeString(eth0.resolve("ds.properties"), String.join("\n",
                "ifInOctets=mib2-interfaces", "ifOutOctets=mib2-interfaces", "ifHCInOctets=mib2-interfaces",
                "ifHCOutOctets=mib2-interfaces", "ifInErrors=mib2-interfaces", "ifInDiscards=mib2-interfaces",
                "ifSpeed=mib2-interfaces", "cpuPercent=mib2-interfaces", ""));
        Files.copy(Fixtures.path("aarch64", "grp.rrd"), eth0.resolve("mib2-interfaces.rrd"));
        Files.writeString(eth0.resolve("strings.properties"), "ifAlias=uplink\n");

        Path fs = Files.createDirectories(root.resolve("snmp/fs/fs1/fid1"));
        Files.writeString(fs.resolve("ds.properties"), "x=node-empty\n");
        Files.copy(Fixtures.path("aarch64", "empty.rrd"), fs.resolve("node-empty.rrd"));

        Path icmp = Files.createDirectories(root.resolve("response/10.0.0.1"));
        Files.writeString(icmp.resolve("ds.properties"), "icmp=icmp\n");
        Files.writeString(icmp.resolve("icmp.meta"), "ICMP/10.0.0.1=icmp\n");
        Files.copy(Fixtures.path("aarch64", "icmp.rrd"), icmp.resolve("icmp.rrd"));
        return root;
    }
}
