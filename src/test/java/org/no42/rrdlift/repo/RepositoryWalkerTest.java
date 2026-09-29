/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryWalkerTest {

    @Test
    void findsStoreByGroupAndResponseResources(@TempDir Path tmp) throws Exception {
        WalkResult result = RepositoryWalker.walk(TestRepos.create(tmp));
        assertThat(result.items()).extracting(WorkItem::resourceId).containsExactly(
                "response/10.0.0.1/icmp",
                "snmp/1/eth0-0011/mib2-interfaces",
                "snmp/fs/fs1/fid1/node-empty");
        assertThat(result.items().get(1).file()).isEqualTo(tmp.resolve("snmp/1/eth0-0011/mib2-interfaces.rrd"));
        assertThat(result.items().get(1).group()).isEqualTo("mib2-interfaces");
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    void findsStoreByMetricResources(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("snmp/2/eth1-0022"));
        Files.writeString(dir.resolve("ifInOctets.meta"), "GROUP=mib2-interfaces\n");
        Files.write(dir.resolve("ifInOctets.jrb"), new byte[] {0});
        Files.writeString(dir.resolve("ifOutOctets.meta"), "GROUP=mib2-interfaces\n");
        Files.write(dir.resolve("ifOutOctets.rrd"), new byte[] {0});

        WalkResult result = RepositoryWalker.walk(tmp);
        assertThat(result.items()).extracting(WorkItem::file).containsExactly(
                dir.resolve("ifInOctets.jrb"), dir.resolve("ifOutOctets.rrd"));
        assertThat(result.items()).extracting(WorkItem::resourceId)
                .containsOnly("snmp/2/eth1-0022/mib2-interfaces");
    }

    @Test
    void prefersNewerFileWhenBothFormatsExist(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("snmp/3/eth0"));
        Files.writeString(dir.resolve("ds.properties"), "ifInOctets=mib2-interfaces\n");
        Path jrb = Files.write(dir.resolve("mib2-interfaces.jrb"), new byte[] {0});
        Path rrd = Files.write(dir.resolve("mib2-interfaces.rrd"), new byte[] {0});
        Files.setLastModifiedTime(jrb, FileTime.fromMillis(2_000_000));
        Files.setLastModifiedTime(rrd, FileTime.fromMillis(1_000_000));

        WalkResult result = RepositoryWalker.walk(tmp);
        assertThat(result.items()).extracting(WorkItem::file).containsExactly(jrb);
        assertThat(result.skipped()).extracting(WalkResult.Skipped::file).containsExactly(rrd);
        assertThat(result.skipped().get(0).reason()).contains("older duplicate");
    }

    @Test
    void reportsFilesWithoutGroupInformation(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("snmp/4"));
        Path stray = Files.write(dir.resolve("mystery.rrd"), new byte[] {0});
        Files.writeString(Files.createDirectories(tmp.resolve("snmp/5")).resolve("ds.properties"), "a=missing\n");

        WalkResult result = RepositoryWalker.walk(tmp);
        assertThat(result.items()).isEmpty();
        assertThat(result.skipped()).extracting(WalkResult.Skipped::reason)
                .containsExactlyInAnyOrder("no group information", "listed in ds.properties but no .rrd or .jrb file");
        assertThat(result.skipped()).extracting(WalkResult.Skipped::file).contains(stray);
    }

    @Test
    void nodePrefixHandlesBothNodeLayouts() {
        assertThat(RepositoryWalker.nodePrefix("snmp/1/eth0-0011/mib2-interfaces")).isEqualTo("snmp/1");
        assertThat(RepositoryWalker.nodePrefix("snmp/fs/fs1/fid1/mib2-tcp")).isEqualTo("snmp/fs/fs1/fid1");
        assertThat(RepositoryWalker.nodePrefix("response/10.0.0.1/icmp")).isEqualTo("response/10.0.0.1");
    }

    @Test
    void handlesISO8859_1BytesInProperties(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("snmp/6/eth2"));
        // Write ds.properties with ISO-8859-1 byte (é = 0xE9): "mib2-interféces"
        Files.write(dir.resolve("ds.properties"),
                new byte[] {0x6D, 0x69, 0x62, 0x32, 0x2D, 0x69, 0x6E, 0x74, 0x65, 0x72, 0x66,
                            (byte)0xE9, 0x63, 0x65, 0x73, 0x3D, 0x6D, 0x69, 0x62, 0x32, 0x2D, 0x69,
                            0x6E, 0x74, 0x65, 0x72, 0x66, (byte)0xE9, 0x63, 0x65, 0x73, 0x0A});
        Files.write(dir.resolve("mib2-interféces.rrd"), new byte[] {0});

        WalkResult result = RepositoryWalker.walk(tmp);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).group()).isEqualTo("mib2-interféces");
        assertThat(result.items().get(0).resourceId()).isEqualTo("snmp/6/eth2/mib2-interféces");
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    void ignoresDirectoriesNamedMeta(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("snmp/7/eth3"));
        Files.createDirectories(dir.resolve("broken.meta"));  // Directory, not a file
        Files.writeString(dir.resolve("ds.properties"), "ifInOctets=mib2-interfaces\n");
        Files.write(dir.resolve("mib2-interfaces.rrd"), new byte[] {0});

        WalkResult result = RepositoryWalker.walk(tmp);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).group()).isEqualTo("mib2-interfaces");
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    void reportsMetaWithGroupButNoFile(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("snmp/8/eth4"));
        Files.writeString(dir.resolve("ifInOctets.meta"), "GROUP=mib2-interfaces\n");
        // No ifInOctets.rrd or ifInOctets.jrb

        WalkResult result = RepositoryWalker.walk(tmp);
        assertThat(result.items()).isEmpty();
        assertThat(result.skipped()).extracting(WalkResult.Skipped::reason)
                .containsExactly("listed in .meta but no .rrd or .jrb file");
        assertThat(result.skipped()).extracting(WalkResult.Skipped::file)
                .containsExactly(dir.resolve("ifInOctets"));
    }
}
