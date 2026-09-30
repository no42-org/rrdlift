/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.opennms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The OpenNMS REST v2 calls rrdlift needs to resolve node-level labels. */
public final class OpennmsClient {

    public record NodeInfo(int id, String label, String location, String foreignSource, String foreignId,
                           List<String> categories, Map<String, String> assets) {}

    public record SnmpInterface(String ifName, String ifDescr, String physAddr, boolean collect) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String base;
    private final String authorization;

    public OpennmsClient(URI baseUrl, String user, String password) {
        this.base = baseUrl.toString().replaceAll("/+$", "");
        this.authorization = user == null ? null : "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /** Fails unless the base URL answers the OpenNMS REST v2 API with these credentials. */
    public void ping() throws IOException {
        HttpResponse<String> r = get("/api/v2/nodes?limit=1");
        if (r.statusCode() != 200 && r.statusCode() != 204) {
            throw new IOException(base + " is not an OpenNMS REST API (HTTP " + r.statusCode() + ")");
        }
    }

    public Optional<NodeInfo> node(String criteria) throws IOException {
        HttpResponse<String> r = get("/api/v2/nodes/" + segment(criteria));
        if (r.statusCode() == 404) {
            return Optional.empty();
        }
        return Optional.of(parseNode(JSON.readTree(r.body())));
    }

    public List<NodeInfo> nodesByIp(String ip) throws IOException {
        HttpResponse<String> r = get("/api/v2/nodes?limit=0&_s="
                + URLEncoder.encode("ipInterface.ipAddress==" + ip, StandardCharsets.UTF_8));
        List<NodeInfo> out = new ArrayList<>();
        if (r.statusCode() == 204) {
            return out;
        }
        for (JsonNode n : JSON.readTree(r.body()).path("node")) {
            out.add(parseNode(n));
        }
        return out;
    }

    public List<SnmpInterface> snmpInterfaces(int nodeId) throws IOException {
        HttpResponse<String> r = get("/api/v2/nodes/" + nodeId + "/snmpinterfaces?limit=0");
        List<SnmpInterface> out = new ArrayList<>();
        if (r.statusCode() == 204 || r.statusCode() == 404) {
            return out;
        }
        for (JsonNode i : JSON.readTree(r.body()).path("snmpInterface")) {
            out.add(new SnmpInterface(text(i, "ifName"), text(i, "ifDescr"), text(i, "physAddr"),
                    i.path("collect").asBoolean(false)));
        }
        return out;
    }

    private HttpResponse<String> get(String pathAndQuery) throws IOException {
        String url = base + pathAndQuery;
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json").GET();
        if (authorization != null) {
            req.header("Authorization", authorization);
        }
        HttpResponse<String> r;
        try {
            r = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        int s = r.statusCode();
        if (s == 401 || s == 403) {
            throw new IOException("OpenNMS REST " + url + " rejected the credentials (HTTP " + s + ")");
        }
        if (s / 100 != 2 && s != 404) {
            throw new IOException("OpenNMS REST " + url + " failed with HTTP " + s);
        }
        return r;
    }

    static NodeInfo parseNode(JsonNode n) {
        List<String> categories = new ArrayList<>();
        for (JsonNode c : n.path("categories")) {
            categories.add(c.path("name").asText());
        }
        Map<String, String> assets = new LinkedHashMap<>();
        n.path("assetRecord").properties().forEach(e -> {
            if (e.getValue().isValueNode() && !e.getValue().isNull() && !e.getValue().asText().isEmpty()) {
                assets.put(e.getKey(), e.getValue().asText());
            }
        });
        return new NodeInfo(n.path("id").asInt(), text(n, "label"), text(n, "location"), text(n, "foreignSource"),
                text(n, "foreignId"), List.copyOf(categories), Map.copyOf(assets));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String segment(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
