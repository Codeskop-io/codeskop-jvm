package com.codeskop.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/** Mock Codeskop ingest API (docs/10 §10.10), validating envelopes like the real backend. */
public final class MockIngest implements AutoCloseable {
    public static final String KEY = "cs_test_pk_abcdefgh12345678";
    private static final Set<String> TYPES = Set.of("api_error", "api_timing", "exception", "crash", "http_request");
    final List<Map<String, Object>> batches = new CopyOnWriteArrayList<>();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<int[]> responses = Collections.synchronizedList(new ArrayList<>()); // {status, retryAfter}
    volatile Map<String, Object> config = new LinkedHashMap<>(Map.of("enabled", true, "features", Map.of("network", true)));
    final Map<String, Object[]> routes = new LinkedHashMap<>();
    final List<String> errors = new CopyOnWriteArrayList<>();
    private final HttpServer server;
    final String url;

    public MockIngest() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(HttpExchange ex, int status, Object body, Map<String, String> headers) throws IOException {
        byte[] b = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    @SuppressWarnings("unchecked")
    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().toString();
        requests.add(ex.getRequestMethod() + " " + path);
        byte[] raw = ex.getRequestBody().readAllBytes();
        if (ex.getRequestMethod().equals("GET") && path.startsWith("/v1/config")) { respond(ex, 200, config, Map.of("ETag", "\"v1\"")); return; }
        for (Map.Entry<String, Object[]> r : routes.entrySet()) {
            if (path.startsWith(r.getKey())) { respond(ex, (Integer) r.getValue()[0], r.getValue()[1], Map.of()); return; }
        }
        if (!ex.getRequestMethod().equals("POST") || !path.startsWith("/v1/events")) { respond(ex, 200, Map.of("ok", true), Map.of()); return; }
        int[] scripted = responses.isEmpty() ? null : responses.remove(0);
        if (scripted != null && scripted[0] != 200) { respond(ex, scripted[0], Map.of("detail", "scripted"), Map.of("Retry-After", String.valueOf(scripted[1]))); return; }
        try {
            if ("gzip".equals(ex.getRequestHeaders().getFirst("Content-Encoding"))) raw = new GZIPInputStream(new ByteArrayInputStream(raw)).readAllBytes();
            Map<String, Object> env = (Map<String, Object>) Json.parse(new String(raw, StandardCharsets.UTF_8));
            if (!("Bearer " + KEY).equals(ex.getRequestHeaders().getFirst("Authorization"))) throw new IllegalStateException("auth");
            if (!String.valueOf(ex.getRequestHeaders().getFirst("User-Agent")).startsWith("codeskop-jvm/")) throw new IllegalStateException("ua");
            List<Object> batch = (List<Object>) env.get("batch");
            if (batch.isEmpty() || batch.size() > 100) throw new IllegalStateException("batch size");
            for (Object o : batch) {
                Map<String, Object> e = (Map<String, Object>) o;
                if (!TYPES.contains(e.get("type"))) throw new IllegalStateException("type " + e.get("type"));
                if (!String.valueOf(e.get("occurred_at")).endsWith("Z")) throw new IllegalStateException("occurred_at");
            }
            batches.add(env);
            respond(ex, 200, Map.of(), Map.of());
        } catch (RuntimeException e) {
            errors.add(e.toString());
            respond(ex, 400, Map.of("errors", e.toString()), Map.of());
        }
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> events() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> b : batches) for (Object e : (List<Object>) b.get("batch")) out.add((Map<String, Object>) e);
        return out;
    }

    List<Map<String, Object>> ofType(String type) {
        return events().stream().filter(e -> type.equals(e.get("type"))).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> payload(Map<String, Object> e) { return (Map<String, Object>) e.get("payload"); }

    @Override
    public void close() { server.stop(0); }
}
