/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.opennms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-process stand-in for the OpenNMS REST v2 endpoints rrdlift uses. */
public final class FakeOpennms implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AUTH = "Basic " + Base64.getEncoder()
            .encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8));

    private final HttpServer server;
    private final Map<String, Map<String, Object>> nodes = new ConcurrentHashMap<>();
    private final Map<String, List<String>> nodesByIp = new ConcurrentHashMap<>();
    private final Map<Integer, List<Map<String, Object>>> snmp = new ConcurrentHashMap<>();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

    private FakeOpennms(HttpServer server) {
        this.server = server;
    }

    public static FakeOpennms start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        FakeOpennms fake = new FakeOpennms(server);
        server.createContext("/opennms/api/v2/nodes", fake::handle);
        server.start();
        return fake;
    }

    public URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/opennms");
    }

    public List<String> requests() {
        return requests;
    }

    public void addNode(int id, String foreignSource, String foreignId, String label, String location,
                        List<String> categories, Map<String, String> assets, String... ips) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", String.valueOf(id));
        n.put("label", label);
        n.put("foreignSource", foreignSource);
        n.put("foreignId", foreignId);
        n.put("location", location);
        List<Map<String, Object>> cats = new ArrayList<>();
        for (String c : categories) {
            cats.add(Map.of("id", cats.size() + 1, "name", c));
        }
        n.put("categories", cats);
        n.put("assetRecord", new LinkedHashMap<>(assets));
        nodes.put(String.valueOf(id), n);
        if (foreignSource != null && foreignId != null) {
            nodes.put(foreignSource + ":" + foreignId, n);
        }
        for (String ip : ips) {
            nodesByIp.computeIfAbsent(ip, k -> new ArrayList<>()).add(String.valueOf(id));
        }
    }

    public void addSnmpInterface(int nodeId, String ifName, String ifDescr, String physAddr, boolean collect) {
        Map<String, Object> i = new LinkedHashMap<>();
        i.put("ifName", ifName);
        i.put("ifDescr", ifDescr);
        i.put("physAddr", physAddr);
        i.put("collect", collect);
        snmp.computeIfAbsent(nodeId, k -> new ArrayList<>()).add(i);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getRawPath().substring("/opennms/api/v2/nodes".length());
        String query = ex.getRequestURI().getRawQuery();
        requests.add(ex.getRequestMethod() + " " + ex.getRequestURI());
        if (!AUTH.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            respond(ex, 401, "");
            return;
        }
        if (path.isEmpty() || path.equals("/")) {
            String s = null;
            if (query != null) {
                for (String kv : query.split("&")) {
                    if (kv.startsWith("_s=")) {
                        s = URLDecoder.decode(kv.substring(3), StandardCharsets.UTF_8);
                    }
                }
            }
            if (s == null) {
                respond(ex, 200, JSON.writeValueAsString(Map.of("node", List.of(), "count", 0)));
                return;
            }
            String ip = s.startsWith("ipInterface.ipAddress==") ? s.substring("ipInterface.ipAddress==".length()) : null;
            List<Map<String, Object>> found = new ArrayList<>();
            for (String id : nodesByIp.getOrDefault(String.valueOf(ip), List.of())) {
                found.add(nodes.get(id));
            }
            if (found.isEmpty()) {
                respond(ex, 204, "");
            } else {
                respond(ex, 200, JSON.writeValueAsString(Map.of("node", found, "count", found.size())));
            }
            return;
        }
        String[] parts = path.substring(1).split("/");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = URLDecoder.decode(parts[i], StandardCharsets.UTF_8);
        }
        if (parts.length == 2 && parts[1].equals("snmpinterfaces")) {
            List<Map<String, Object>> list = snmp.getOrDefault(Integer.parseInt(parts[0]), List.of());
            if (list.isEmpty()) {
                respond(ex, 204, "");
            } else {
                respond(ex, 200, JSON.writeValueAsString(Map.of("snmpInterface", list, "count", list.size())));
            }
            return;
        }
        Map<String, Object> node = nodes.get(parts[0]);
        if (node == null) {
            respond(ex, 404, "");
        } else {
            respond(ex, 200, JSON.writeValueAsString(node));
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, status == 204 || bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0 && status != 204) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
        ex.close();
    }
}
