package com.codeskop.server;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * API Trust capture (docs/10 §10.9), active only when remote config contains {@code api_trust}.
 * Consumer credentials are HMAC-hashed here; raw values never leave your server.
 * Blocking is opt-in and fails open.
 */
public final class Trust {
    private static final long VERDICT_REFRESH_MS = 60_000;
    private final Client client;
    volatile boolean active;
    private volatile List<Map<String, Object>> sources = new ArrayList<>();
    private volatile String salt = "";
    private volatile boolean trustProxy = true;
    private volatile boolean blocking;
    private volatile String verdictsPath = "/v1/trust/verdicts";
    private volatile Set<String> blocked = Collections.emptySet();
    private volatile String verdictEtag;
    private volatile long verdictsAt;
    private volatile boolean refreshing;

    Trust(Client client) { this.client = client; }

    @SuppressWarnings("unchecked")
    void configure(Map<String, Object> cfg) {
        salt = String.valueOf(cfg.getOrDefault("salt", ""));
        active = Boolean.TRUE.equals(cfg.get("enabled")) && !salt.isEmpty();
        List<Map<String, Object>> src = new ArrayList<>();
        Object raw = cfg.get("consumer_sources");
        if (raw instanceof List) for (Object o : (List<Object>) raw) if (o instanceof Map) src.add((Map<String, Object>) o);
        sources = src;
        trustProxy = client.options.trustProxy != null ? client.options.trustProxy : !Boolean.FALSE.equals(cfg.get("trust_proxy"));
        blocking = Boolean.TRUE.equals(cfg.get("blocking"));
        verdictsPath = String.valueOf(cfg.getOrDefault("verdicts_path", "/v1/trust/verdicts"));
        if (!blocking) blocked = Collections.emptySet();
        else if (verdictsAt == 0) fetchVerdicts();
    }

    String hash(String raw) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] d = mac.doFinal(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Result of inspecting one request. */
    public static final class Inspection {
        public final Map<String, Object> extra;
        public final boolean blocked;
        Inspection(Map<String, Object> extra, boolean blocked) { this.extra = extra; this.blocked = blocked; }
    }

    Inspection inspect(Object request, Function<String, String> header, Function<String, String> query, String peer) {
        Map<String, Object> extra = new LinkedHashMap<>();
        Map<String, Object> consumer = consumer(request, header, query);
        if (consumer != null) extra.put("consumer", consumer);
        Map<String, Object> c = new LinkedHashMap<>();
        put(c, "ip", clientIp(header, peer), 64);
        put(c, "user_agent", header.apply("user-agent"), 300);
        put(c, "origin", header.apply("origin"), 300);
        put(c, "referer", header.apply("referer"), 300);
        put(c, "requested_with", header.apply("x-requested-with"), 200);
        if (!c.isEmpty()) extra.put("client", c);
        boolean isBlocked = consumer != null && blocking && blockedSet().contains((String) consumer.get("id_hash"));
        return new Inspection(extra, isBlocked);
    }

    private static void put(Map<String, Object> m, String k, String v, int max) {
        if (v != null && !v.isEmpty()) m.put(k, v.length() > max ? v.substring(0, max) : v);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> consumer(Object request, Function<String, String> header, Function<String, String> query) {
        Function<Object, String> resolver = client.options.apiTrustResolver;
        if (resolver != null) {
            try {
                String raw = resolver.apply(request);
                if (raw != null && !raw.isEmpty()) return consumerMap(raw, "custom", "resolver");
            } catch (Exception ignored) { /* fall back to sources */ }
        }
        for (Map<String, Object> s : sources) {
            String type = String.valueOf(s.get("type"));
            String raw = null;
            String auth = "api_key";
            String label = type;
            if ("header".equals(type)) {
                String name = String.valueOf(s.get("name"));
                raw = header.apply(name.toLowerCase());
                Object scheme = s.get("scheme");
                if (raw != null && scheme != null) {
                    String prefix = scheme + " ";
                    raw = raw.regionMatches(true, 0, prefix, 0, prefix.length()) ? raw.substring(prefix.length()) : null;
                }
                label = "header:" + name;
            } else if ("query".equals(type)) {
                String name = String.valueOf(s.get("name"));
                raw = query.apply(name);
                label = "query:" + name;
            } else if ("jwt".equals(type)) {
                Object claims = s.get("claims");
                raw = jwtClaim(header.apply(String.valueOf(s.getOrDefault("header", "Authorization")).toLowerCase()),
                    claims instanceof List ? (List<Object>) claims : List.of("sub"));
                auth = "jwt";
                label = "jwt";
            } else if ("mtls".equals(type)) {
                String name = String.valueOf(s.get("header"));
                raw = header.apply(name.toLowerCase());
                auth = "mtls";
                label = "mtls:" + name;
            }
            if (raw != null && !raw.trim().isEmpty()) return consumerMap(raw.trim(), auth, label);
        }
        return null;
    }

    private Map<String, Object> consumerMap(String raw, String auth, String source) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id_hash", hash(raw));
        m.put("auth_type", auth);
        m.put("source", source.length() > 80 ? source.substring(0, 80) : source);
        return m;
    }

    String clientIp(Function<String, String> header, String peer) {
        if (trustProxy) {
            String fwd = header.apply("x-forwarded-for");
            if (fwd != null && !fwd.isEmpty()) return fwd.split(",")[0].trim();
            String real = header.apply("x-real-ip");
            if (real != null && !real.isEmpty()) return real.trim();
        }
        return peer;
    }

    private static String jwtClaim(String header, List<Object> claims) {
        if (header == null) return null;
        String token = header.regionMatches(true, 0, "bearer ", 0, 7) ? header.substring(7) : header;
        String[] parts = token.split("\\.");
        if (parts.length != 3) return null;
        try {
            Object payload = Json.parse(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            if (!(payload instanceof Map)) return null;
            for (Object c : claims) {
                Object v = ((Map<?, ?>) payload).get(String.valueOf(c));
                if (v != null && !String.valueOf(v).isEmpty()) return String.valueOf(v);
            }
        } catch (Exception ignored) { /* not a JWT */ }
        return null;
    }

    private Set<String> blockedSet() {
        if (System.currentTimeMillis() - verdictsAt > VERDICT_REFRESH_MS && !refreshing) {
            Thread t = new Thread(this::fetchVerdicts, "codeskop-verdicts");
            t.setDaemon(true);
            t.start();
        }
        return blocked;
    }

    @SuppressWarnings("unchecked")
    private void fetchVerdicts() {
        refreshing = true;
        verdictsAt = System.currentTimeMillis();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(client.options.endpoint + verdictsPath)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + client.options.apiKey).header("User-Agent", Client.USER_AGENT);
            if (verdictEtag != null) b.header("If-None-Match", verdictEtag);
            HttpResponse<String> res = client.http().send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                Object body = Json.parse(res.body());
                Set<String> s = new HashSet<>();
                if (body instanceof Map && ((Map<String, Object>) body).get("blocked") instanceof List)
                    for (Object o : (List<Object>) ((Map<String, Object>) body).get("blocked")) s.add(String.valueOf(o));
                blocked = s;
                verdictEtag = res.headers().firstValue("etag").orElse(null);
            }
        } catch (Exception ignored) {
            // fail open
        } finally {
            refreshing = false;
        }
    }
}
