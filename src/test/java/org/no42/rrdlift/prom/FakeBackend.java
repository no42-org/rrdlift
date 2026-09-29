/*
 * Copyright 2026 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.no42.rrdlift.prom;

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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.xerial.snappy.Snappy;

/** In-process stand-in for a Prometheus-compatible backend; supports what rrdlift sends. */
public final class FakeBackend implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern MATCHER = Pattern.compile("(\\w+)(=~|=)\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern RANGE = Pattern.compile("^(\\{.*\\})\\[(\\d+)s\\]$");

    private final HttpServer server;
    private final Map<Map<String, String>, TreeMap<Long, Double>> data = new ConcurrentHashMap<>();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger writes = new AtomicInteger();
    private volatile long rejectOlderThanMs = Long.MIN_VALUE;
    private volatile long dropOlderThanMs = Long.MIN_VALUE;
    private volatile int failWrites;
    private volatile int failStatus;

    private FakeBackend(HttpServer server) {
        this.server = server;
    }

    public static FakeBackend start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        FakeBackend backend = new FakeBackend(server);
        server.createContext("/api/v1/write", backend::write);
        server.createContext("/api/v1/series", backend::series);
        server.createContext("/api/v1/query", backend::query);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
        return backend;
    }

    public URI writeUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/write");
    }

    public URI readUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public Map<Map<String, String>, TreeMap<Long, Double>> data() {
        return data;
    }

    public List<String> requests() {
        return requests;
    }

    public int writeCount() {
        return writes.get();
    }

    public void rejectOlderThanMs(long ms) {
        rejectOlderThanMs = ms;
    }

    public void dropOlderThanMs(long ms) {
        dropOlderThanMs = ms;
    }

    public synchronized void failNextWrites(int count, int status) {
        failWrites = count;
        failStatus = status;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void write(HttpExchange ex) throws IOException {
        requests.add("POST " + ex.getRequestURI() + " X-Scope-OrgID=" + ex.getRequestHeaders().getFirst("X-Scope-OrgID"));
        byte[] body = Snappy.uncompress(ex.getRequestBody().readAllBytes());
        writes.incrementAndGet();
        synchronized (this) {
            if (failWrites > 0) {
                failWrites--;
                respond(ex, failStatus, "injected failure");
                return;
            }
        }
        List<TimeSeries> series = RemoteWriteEncoder.decode(body);
        for (TimeSeries ts : series) {
            for (long t : ts.timesMs()) {
                if (t < rejectOlderThanMs) {
                    respond(ex, 400, "out of bounds: sample at " + t);
                    return;
                }
            }
        }
        for (TimeSeries ts : series) {
            TreeMap<Long, Double> points = data.computeIfAbsent(new TreeMap<>(ts.labels()), k -> new TreeMap<>());
            synchronized (points) {
                for (int i = 0; i < ts.timesMs().length; i++) {
                    if (ts.timesMs()[i] >= dropOlderThanMs) {
                        points.put(ts.timesMs()[i], ts.values()[i]);
                    }
                }
            }
        }
        respond(ex, 204, "");
    }

    private void series(HttpExchange ex) throws IOException {
        Map<String, String> q = params(ex);
        requests.add("GET series " + q.get("match[]"));
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> labels : data.keySet()) {
            if (matches(q.get("match[]"), labels)) {
                out.add(labels);
            }
        }
        respondJson(ex, out);
    }

    private void query(HttpExchange ex) throws IOException {
        Map<String, String> q = params(ex);
        String promql = q.get("query");
        long timeMs = Math.round(Double.parseDouble(q.get("time")) * 1000);
        requests.add("GET query " + promql + " @" + timeMs);
        Matcher range = RANGE.matcher(promql);
        List<Map<String, Object>> result = new ArrayList<>();
        String selector = range.matches() ? range.group(1) : promql;
        long fromMs = range.matches() ? timeMs - Long.parseLong(range.group(2)) * 1000 : timeMs - 300_000;
        for (Map.Entry<Map<String, String>, TreeMap<Long, Double>> e : data.entrySet()) {
            if (!matches(selector, e.getKey())) {
                continue;
            }
            List<List<Object>> values = new ArrayList<>();
            synchronized (e.getValue()) {
                e.getValue().subMap(fromMs, false, timeMs, true).forEach((t, v) ->
                        values.add(List.of(t / 1000.0, Double.toString(v))));
            }
            if (values.isEmpty()) {
                continue;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("metric", e.getKey());
            if (range.matches()) {
                r.put("values", values);
            } else {
                r.put("value", values.get(values.size() - 1));
            }
            result.add(r);
        }
        respondJson(ex, Map.of("resultType", range.matches() ? "matrix" : "vector", "result", result));
    }

    static boolean matches(String selector, Map<String, String> labels) {
        Matcher m = MATCHER.matcher(selector);
        boolean any = false;
        while (m.find()) {
            any = true;
            String value = m.group(3).replace("\\\"", "\"").replace("\\\\", "\\");
            String actual = labels.get(m.group(1));
            if (actual == null) {
                return false;
            }
            boolean ok = m.group(2).equals("=") ? actual.equals(value) : Pattern.matches(value, actual);
            if (!ok) {
                return false;
            }
        }
        return any;
    }

    private static Map<String, String> params(HttpExchange ex) {
        Map<String, String> out = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String kv : raw.split("&")) {
                int i = kv.indexOf('=');
                out.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static void respondJson(HttpExchange ex, Object data) throws IOException {
        respond(ex, 200, JSON.writeValueAsString(Map.of("status", "success", "data", data)));
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (status != 204) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
        ex.close();
    }
}
