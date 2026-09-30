/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.opennms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpennmsClientTest {

    FakeOpennms onms;
    OpennmsClient client;

    @BeforeEach
    void start() throws Exception {
        onms = FakeOpennms.start();
        onms.addNode(1, "fs1", "fid1", "router1", "dc1", List.of("Routers", "Core"),
                Map.of("building", "B1", "modelNumber", "MX480"), "10.0.0.1");
        onms.addNode(2, "My Source", "id:7", "odd", "dc2", List.of(), Map.of(), "fe80::1");
        onms.addSnmpInterface(1, "ge-0/0/1", "ge-0/0/1 descr", "001122334455", true);
        client = new OpennmsClient(onms.url(), "admin", "admin");
    }

    @AfterEach
    void stop() {
        onms.close();
    }

    @Test
    void readsNodeByIdAndByForeignId() throws Exception {
        OpennmsClient.NodeInfo n = client.node("1").orElseThrow();
        assertThat(n.id()).isEqualTo(1);
        assertThat(n.label()).isEqualTo("router1");
        assertThat(n.location()).isEqualTo("dc1");
        assertThat(n.foreignSource()).isEqualTo("fs1");
        assertThat(n.foreignId()).isEqualTo("fid1");
        assertThat(n.categories()).containsExactly("Routers", "Core");
        assertThat(n.assets()).containsEntry("building", "B1").containsEntry("modelNumber", "MX480");
        assertThat(client.node("fs1:fid1")).map(OpennmsClient.NodeInfo::id).contains(1);
    }

    @Test
    void missingNodeIsEmpty() throws Exception {
        assertThat(client.node("99")).isEmpty();
    }

    @Test
    void findsNodesByIpAndHandlesNoContent() throws Exception {
        assertThat(client.nodesByIp("10.0.0.1")).extracting(OpennmsClient.NodeInfo::label).containsExactly("router1");
        assertThat(client.nodesByIp("10.9.9.9")).isEmpty();
    }

    @Test
    void readsSnmpInterfaces() throws Exception {
        assertThat(client.snmpInterfaces(1)).containsExactly(
                new OpennmsClient.SnmpInterface("ge-0/0/1", "ge-0/0/1 descr", "001122334455", true));
        assertThat(client.snmpInterfaces(2)).isEmpty();
    }

    @Test
    void wrongCredentialsNameTheProblem() {
        OpennmsClient bad = new OpennmsClient(onms.url(), "admin", "wrong");
        assertThatThrownBy(() -> bad.node("1")).isInstanceOf(IOException.class)
                .hasMessageContaining("rejected the credentials");
    }

    @Test
    void encodesCriteriaWithSpacesAndColons() throws Exception {
        assertThat(client.node("My Source:id:7")).map(OpennmsClient.NodeInfo::id).contains(2);
        assertThat(onms.requests()).anyMatch(r -> r.contains("/api/v2/nodes/My%20Source%3Aid%3A7"));
    }

    @Test
    void findsNodesByIpv6() throws Exception {
        assertThat(client.nodesByIp("fe80::1")).extracting(OpennmsClient.NodeInfo::id).containsExactly(2);
    }

    @Test
    void trailingSlashInBaseUrlWorks() throws Exception {
        OpennmsClient slash = new OpennmsClient(java.net.URI.create(onms.url() + "/"), "admin", "admin");
        assertThat(slash.node("1")).isPresent();
    }

    @Test
    void pingAcceptsARealRestApi() throws Exception {
        client.ping();
    }

    @Test
    void pingRejectsAWrongPrefix() {
        OpennmsClient wrong = new OpennmsClient(java.net.URI.create(onms.url() + "/wrong"), "admin", "admin");
        assertThatThrownBy(wrong::ping).isInstanceOf(IOException.class)
                .hasMessageContaining("is not an OpenNMS REST API");
    }

    @Test
    void pingRejectsBadCredentials() {
        OpennmsClient bad = new OpennmsClient(onms.url(), "admin", "wrong");
        assertThatThrownBy(bad::ping).isInstanceOf(IOException.class)
                .hasMessageContaining("rejected the credentials");
    }
}
