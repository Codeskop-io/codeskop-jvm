package com.codeskop.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * Queue, sampling, remote config and the background sender (docs/10 §10.4–10.5).
 * One daemon thread batches, compresses and sends; nothing blocks the caller.
 */
public final class Client {
    static final Logger LOG = Logger.getLogger("codeskop");
    static final String USER_AGENT = "codeskop-jvm/" + Codeskop.VERSION;
    private static final int MAX_BATCH = 100;
    private static final int MAX_EVENT_BYTES = 64 * 1024;
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final long CONFIG_REFRESH_MS = 300_000;
    private static final Pattern PUBLIC_KEY = Pattern.compile("^cs_(live|test)_pk_[A-Za-z0-9_-]{8,}$");

    final Codeskop.Options options;
    final boolean enabled;
    volatile Map<String, Object> remote = new LinkedHashMap<>();
    volatile Trust trust;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Deque<Map<String, Object>> queue = new ArrayDeque<>();
    private final Object lock = new Object();
    private List<Map<String, Object>> pending;
    private int attempt;
    private long nextSendAt;
    private String etag;
    private long configAt;
    private volatile boolean closed;
    private volatile boolean flushRequested;
    private final Thread worker;
    private final Semaphore wake = new Semaphore(0);
    private final List<Pattern> ignoreRoutes = new ArrayList<>();
    private final String hostname;

    Client(Codeskop.Options options) {
        this.options = options;
        String problem = keyProblem(options.apiKey);
        this.enabled = options.enabled && problem == null;
        if (problem != null) LOG.warning("codeskop: disabled: " + problem);
        for (String g : options.ignoreRoutes) ignoreRoutes.add(Pattern.compile("^" + Pattern.quote(g).replace("*", "\\E.*\\Q") + "$"));
        String h;
        try { h = InetAddress.getLocalHost().getHostName(); } catch (Exception e) { h = ""; }
        hostname = h;
        worker = new Thread(this::run, "codeskop-worker");
        worker.setDaemon(true);
        if (enabled) worker.start();
    }

    static String keyProblem(String key) {
        if (key == null || key.isEmpty()) return "no apiKey (set CODESKOP_API_KEY or Options.apiKey)";
        if (key.contains("_sk_")) return "a secret key (_sk_) was given; use the project's public key (cs_..._pk_...)";
        if (!PUBLIC_KEY.matcher(key).matches()) return "the apiKey doesn't look like a Codeskop public key";
        return null;
    }

    // -- remote config & sampling --------------------------------------------------

    @SuppressWarnings("unchecked")
    void refreshConfig(boolean force) {
        if (!force && System.currentTimeMillis() - configAt < CONFIG_REFRESH_MS) return;
        configAt = System.currentTimeMillis();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(options.endpoint + "/v1/config")).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + options.apiKey).header("User-Agent", USER_AGENT);
            if (etag != null) b.header("If-None-Match", etag);
            HttpResponse<String> res = http.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                Object parsed = Json.parse(res.body());
                if (parsed instanceof Map) {
                    remote = (Map<String, Object>) parsed;
                    etag = res.headers().firstValue("etag").orElse(null);
                    if (Boolean.FALSE.equals(remote.get("enabled"))) synchronized (lock) { queue.clear(); pending = null; }
                    Object tc = remote.get("api_trust");
                    if (tc instanceof Map && Boolean.TRUE.equals(((Map<String, Object>) tc).get("enabled")) && trust == null) trust = new Trust(this);
                    if (trust != null) trust.configure(tc instanceof Map ? (Map<String, Object>) tc : new LinkedHashMap<>());
                }
            } else if (res.statusCode() == 401 || res.statusCode() == 403) {
                LOG.warning("codeskop: the API key was refused (HTTP " + res.statusCode() + "); events won't be accepted");
            }
        } catch (Exception e) {
            if (options.debug) LOG.log(Level.FINE, "codeskop: config fetch failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    boolean feature(String name) {
        Object f = remote.get("features");
        if (f instanceof Map && ((Map<String, Object>) f).containsKey(name)) return Boolean.TRUE.equals(((Map<String, Object>) f).get(name));
        return true;
    }

    @SuppressWarnings("unchecked")
    double sampleRate(String type) {
        Object r = remote.get("sample_rates");
        Map<String, Object> remoteRates = r instanceof Map ? (Map<String, Object>) r : Map.of();
        for (Map<String, ?> src : List.<Map<String, ?>>of(remoteRates, options.sampleRates)) {
            if (src.containsKey(type)) return clamp(src.get(type));
            if ("http_request".equals(type) && src.containsKey("api_timing")) return clamp(src.get("api_timing"));
        }
        return 1.0;
    }

    @SuppressWarnings("unchecked")
    boolean keep(Map<String, Object> e) {
        String type = (String) e.get("type");
        if ("exception".equals(type) || "api_error".equals(type) || "high".equals(e.get("severity"))) return true;
        if ("http_request".equals(type) && ((Map<String, Object>) e.get("payload")).containsKey("consumer")) return true;
        double rate = sampleRate(type);
        return rate >= 1.0 || ThreadLocalRandom.current().nextDouble() < rate;
    }

    boolean ignoredRoute(String route) {
        for (Pattern p : ignoreRoutes) if (p.matcher(route).matches()) return true;
        return false;
    }

    // -- capture --------------------------------------------------------------------

    Map<String, Object> context() {
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("platform", "jvm");
        device.put("hostname", hostname);
        device.put("os", System.getProperty("os.name", "") + " " + System.getProperty("os.version", ""));
        device.put("runtime", System.getProperty("java.vm.name", "Java") + " " + System.getProperty("java.version", ""));
        Map<String, Object> app = new LinkedHashMap<>();
        if (options.release != null) app.put("release", options.release);
        app.put("environment", options.environment);
        if (Codeskop.framework != null) app.put("framework", Codeskop.framework);
        app.put("sdk_name", "codeskop-jvm");
        app.put("sdk_version", Codeskop.VERSION);
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("device", device);
        ctx.put("app", app);
        return ctx;
    }

    void capture(Map<String, Object> event) {
        if (!enabled || closed || Boolean.FALSE.equals(remote.get("enabled"))) return;
        try {
            String type = (String) event.get("type");
            if (("http_request".equals(type) || "api_timing".equals(type) || "api_error".equals(type)) && !feature("network")) return;
            if (!keep(event)) return;
            if (options.beforeSend != null) {
                event = options.beforeSend.apply(event);
                if (event == null) return;
            }
            boolean full;
            synchronized (lock) {
                if (queue.size() >= options.maxQueueEvents) queue.pollFirst();
                queue.addLast(event);
                full = queue.size() >= MAX_BATCH;
            }
            if (full) wake.release();
        } catch (Exception e) {
            if (options.debug) LOG.log(Level.FINE, "codeskop: capture failed", e);
        }
    }

    // -- sending --------------------------------------------------------------------

    private void run() {
        refreshConfig(true);
        while (!closed) {
            try {
                // Woken early (without interrupting an in-flight send) by a full batch or a flush.
                if (wake.tryAcquire(options.flushIntervalMs, TimeUnit.MILLISECONDS)) wake.drainPermits();
            } catch (InterruptedException e) {
                return;
            }
            try {
                refreshConfig(false);
                drain(flushRequested);
            } catch (Exception e) {
                if (options.debug) LOG.log(Level.FINE, "codeskop: worker error", e);
            }
            flushRequested = false;
        }
    }

    private static final class Result {
        final boolean done;
        final double retryAfter;
        Result(boolean done, double retryAfter) { this.done = done; this.retryAfter = retryAfter; }
    }

    private Result send(byte[] body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(options.endpoint + "/v1/events")).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + options.apiKey).header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json").header("Content-Encoding", "gzip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            int s = res.statusCode();
            if (s >= 200 && s < 300) return new Result(true, 0);
            if (s == 429 || s == 503) return new Result(false, retryAfter(res.headers().firstValue("retry-after").orElse(null)));
            if (s >= 500) return new Result(false, backoff(attempt));
            LOG.warning("codeskop: ingest refused a batch (HTTP " + s + "); dropping it");
            return new Result(true, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, backoff(attempt));
        } catch (Exception e) {
            return new Result(false, backoff(attempt));
        }
    }

    private void drain(boolean force) {
        while (true) {
            if (!force && System.currentTimeMillis() < nextSendAt) return;
            List<Map<String, Object>> batch;
            synchronized (lock) {
                if (pending == null) {
                    if (queue.isEmpty()) return;
                    pending = new ArrayList<>();
                    while (!queue.isEmpty() && pending.size() < MAX_BATCH) pending.add(queue.pollFirst());
                    attempt = 0;
                }
                batch = pending;
            }
            Result failure = null;
            for (byte[] body : bodies(batch)) {
                Result r = send(body);
                if (!r.done) { failure = r; break; }
            }
            if (failure == null) {
                synchronized (lock) { pending = null; attempt = 0; }
                continue;
            }
            attempt++;
            nextSendAt = System.currentTimeMillis() + (long) (failure.retryAfter * 1000);
            if (force && !closed && attempt < 3) {
                sleepQuietly((long) (Math.min(failure.retryAfter, 1.0) * 1000));
                continue;
            }
            return;
        }
    }

    private List<byte[]> bodies(List<Map<String, Object>> batch) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> e : batch) if (Json.write(e).length() <= MAX_EVENT_BYTES) events.add(e);
        List<byte[]> out = new ArrayList<>();
        if (!events.isEmpty()) split(events, out);
        return out;
    }

    private void split(List<Map<String, Object>> events, List<byte[]> out) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("sent_at", Events.now());
        env.put("context", context());
        env.put("batch", events);
        byte[] body = gzip(Json.write(env));
        if (body.length <= MAX_BODY_BYTES || events.size() == 1) {
            out.add(body);
            return;
        }
        int mid = events.size() / 2;
        split(new ArrayList<>(events.subList(0, mid)), out);
        split(new ArrayList<>(events.subList(mid, events.size())), out);
    }

    /** Block until everything queued is sent, or the timeout passes. */
    boolean flush(long timeoutMs) {
        if (!enabled) return true;
        long deadline = System.currentTimeMillis() + timeoutMs;
        nextSendAt = 0;
        flushRequested = true;
        if (Thread.currentThread() == worker || !worker.isAlive()) {
            drain(true);
        } else {
            wake.release();
        }
        while (System.currentTimeMillis() < deadline) {
            synchronized (lock) { if (queue.isEmpty() && pending == null) return true; }
            sleepQuietly(20);
        }
        return false;
    }

    void close(long timeoutMs) {
        if (!enabled || closed) return;
        flush(timeoutMs);
        closed = true;
        wake.release();
    }

    /** Shutdown-hook / crash path: send synchronously on the calling thread. */
    void flushNow() {
        if (!enabled) return;
        nextSendAt = 0;
        drain(true);
    }

    private static byte[] gzip(String s) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) { gz.write(s.getBytes(StandardCharsets.UTF_8)); }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static double clamp(Object v) {
        try { return Math.max(0, Math.min(1, Double.parseDouble(String.valueOf(v)))); } catch (Exception e) { return 1; }
    }

    private static double retryAfter(String v) {
        try { double d = Double.parseDouble(v); return d >= 0 ? d : 5; } catch (Exception e) { return 5; }
    }

    private static double backoff(int attempt) {
        return Math.min(60, Math.pow(2, Math.max(0, attempt))) * (0.8 + ThreadLocalRandom.current().nextDouble() * 0.4);
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    HttpClient http() { return http; }
}
