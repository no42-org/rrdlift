/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.prom;

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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.xerial.snappy.Snappy;

/** Remote write plus the Prometheus HTTP API endpoints rrdlift reads. */
public final class PromClient {

    public record QueryResult(Map<String, String> metric, long[] timesMs, double[] values) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final URI writeUrl;
    private final URI readUrl;
    private final String orgId;

    /** {@code readUrl} is the API base, e.g. {@code http://prometheus:9090}; paths /api/v1/... are appended. */
    public PromClient(URI writeUrl, URI readUrl, String orgId) {
        this.writeUrl = writeUrl;
        this.readUrl = readUrl;
        this.orgId = orgId == null || orgId.isEmpty() ? null : orgId;
    }

    public void write(List<TimeSeries> series) throws IOException {
        byte[] body = Snappy.compress(RemoteWriteEncoder.encode(series));
        HttpRequest.Builder req = HttpRequest.newBuilder(writeUrl)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Encoding", "snappy")
                .header("Content-Type", "application/x-protobuf")
                .header("X-Prometheus-Remote-Write-Version", "0.1.0")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<String> resp = send(tenant(req));
        if (resp.statusCode() / 100 != 2) {
            throw new WriteException(resp.statusCode(), resp.body());
        }
    }

    public List<Map<String, String>> series(String selector, long startSec, long endSec) throws IOException {
        JsonNode data = get("/api/v1/series?match[]=" + enc(selector) + "&start=" + startSec + "&end=" + endSec);
        List<Map<String, String>> out = new ArrayList<>();
        for (JsonNode s : data) {
            out.add(labels(s));
        }
        return out;
    }

    public List<QueryResult> query(String promql, long timeSec) throws IOException {
        JsonNode data = get("/api/v1/query?query=" + enc(promql) + "&time=" + timeSec);
        List<QueryResult> out = new ArrayList<>();
        for (JsonNode r : data.path("result")) {
            JsonNode points = r.has("values") ? r.get("values") : JSON.createArrayNode().add(r.get("value"));
            long[] times = new long[points.size()];
            double[] values = new double[points.size()];
            for (int i = 0; i < points.size(); i++) {
                times[i] = Math.round(points.get(i).get(0).asDouble() * 1000);
                values[i] = Double.parseDouble(points.get(i).get(1).asText());
            }
            out.add(new QueryResult(labels(r.get("metric")), times, values));
        }
        return out;
    }

    private JsonNode get(String pathAndQuery) throws IOException {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(readUrl.toString().replaceAll("/+$", "") + pathAndQuery))
                .timeout(Duration.ofSeconds(60)).GET();
        HttpResponse<String> resp = send(tenant(req));
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("GET " + pathAndQuery + " failed with HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = JSON.readTree(resp.body());
        if (!"success".equals(root.path("status").asText())) {
            throw new IOException("GET " + pathAndQuery + " returned " + resp.body());
        }
        return root.path("data");
    }

    private HttpRequest tenant(HttpRequest.Builder req) {
        if (orgId != null) {
            req.header("X-Scope-OrgID", orgId);
        }
        return req.build();
    }

    private HttpResponse<String> send(HttpRequest req) throws IOException {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static Map<String, String> labels(JsonNode node) {
        Map<String, String> m = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            m.put(e.getKey(), e.getValue().asText());
        }
        return m;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
