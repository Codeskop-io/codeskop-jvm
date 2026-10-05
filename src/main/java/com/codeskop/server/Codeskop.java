package com.codeskop.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Codeskop server SDK for the JVM.
 *
 * <pre>{@code
 * Codeskop.init(Codeskop.options().apiKey("cs_live_pk_…").environment("production"));
 * }</pre>
 *
 * Every method is safe to call before {@code init} (it does nothing) and never throws.
 */
public final class Codeskop {
    public static final String VERSION = "0.1.0";
    static volatile Client client;
    static volatile String framework;
    private static final ThreadLocal<String> USER = new ThreadLocal<>();
    private static boolean hooksInstalled;

    private Codeskop() {}

    /** Start the SDK. Calling it again replaces the previous configuration. */
    public static synchronized Client init(Options options) {
        Client old = client;
        if (old != null) old.close(500);
        client = new Client(options);
        installHooks();
        return client;
    }

    public static Options options() { return new Options(); }

    public static Client client() { return client; }

    public static void captureException(Throwable error) { captureException(error, null, true, "manual", null); }

    public static void captureException(Throwable error, Map<String, String> tags) { captureException(error, tags, true, "manual", null); }

    static void captureException(Throwable error, Map<String, String> tags, boolean handled, String mechanism, Map<String, Object> request) {
        Client c = client;
        if (c == null || !c.enabled || error == null) return;
        try {
            if (c.options.ignoreExceptions.contains(error.getClass().getName()) || c.options.ignoreExceptions.contains(error.getClass().getSimpleName())) return;
            c.capture(Events.exception(error, handled, mechanism, request, currentUser(), tags));
        } catch (Exception e) {
            Client.LOG.log(Level.FINE, "codeskop: captureException failed", e);
        }
    }

    public static void captureMessage(String message) { captureMessage(message, "medium"); }

    public static void captureMessage(String message, String severity) {
        Client c = client;
        if (c == null || !c.enabled || message == null) return;
        c.capture(Events.message(message, severity, currentUser()));
    }

    /** Attach later events on this thread (this request) to a user: your own ID, never an email. */
    public static void setUser(Object userId) {
        if (userId == null) USER.remove();
        else USER.set(String.valueOf(userId));
    }

    static String currentUser() {
        Client c = client;
        return c != null && c.options.sendUserId ? USER.get() : null;
    }

    static void clearUser() { USER.remove(); }

    /** The user ID set for this thread, or null (integrations use it to avoid overwriting your own). */
    public static String currentUserOrNull() { return USER.get(); }

    /** Integrations name the framework once, e.g. "spring". */
    public static void setFrameworkIfUnset(String name) {
        if (framework == null || "servlet".equals(framework)) framework = name;
    }

    /** Record one outgoing HTTP call (OkHttp and java.net.http integrations). Never the Codeskop endpoint. */
    public static void recordOutgoing(String method, String host, String path, Integer status, double durationMs, String errorKind) {
        Client c = client;
        if (c == null || !c.enabled || !c.options.captureOutgoing || host == null || host.isEmpty()) return;
        try {
            if (java.net.URI.create(c.options.endpoint).getHost().equalsIgnoreCase(host)) return;
            for (Map<String, Object> e : Events.outgoing(method, host, path, status, durationMs, errorKind, currentUser())) c.capture(e);
        } catch (RuntimeException e) {
            Client.LOG.log(Level.FINE, "codeskop: recordOutgoing failed", e);
        }
    }

    public static boolean flush(long timeoutMs) { Client c = client; return c == null || c.flush(timeoutMs); }

    public static void close(long timeoutMs) { Client c = client; if (c != null) c.close(timeoutMs); }

    private static synchronized void installHooks() {
        if (hooksInstalled) return;
        hooksInstalled = true;
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            captureException(error, null, false, "uncaught", null);
            Client c = client;
            if (c != null) c.flushNow();
            if (previous != null) previous.uncaughtException(thread, error);
            else error.printStackTrace();
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Client c = client;
            if (c != null) c.flushNow();
        }, "codeskop-shutdown"));
    }

    /** SDK options (docs/10 §10.3). Defaults read CODESKOP_* environment variables. */
    public static final class Options {
        String apiKey = env("CODESKOP_API_KEY", "");
        String endpoint = env("CODESKOP_ENDPOINT", "https://api.codeskop.com");
        String environment = env("CODESKOP_ENVIRONMENT", "production");
        String release = detectRelease();
        boolean captureRequests = true;
        boolean captureOutgoing = true;
        Map<String, Double> sampleRates = new LinkedHashMap<>();
        List<String> ignoreRoutes = new ArrayList<>(Arrays.asList("/health*", "/healthz", "/metrics", "/favicon.ico", "/actuator/*"));
        List<String> ignoreExceptions = new ArrayList<>();
        Function<Map<String, Object>, Map<String, Object>> beforeSend;
        boolean sendUserId = true;
        int maxQueueEvents = 10_000;
        long flushIntervalMs = 5_000;
        boolean debug;
        boolean enabled = true;
        Function<Object, String> apiTrustResolver;
        Boolean trustProxy;

        public Options apiKey(String v) { apiKey = v == null ? "" : v.trim(); return this; }
        public Options endpoint(String v) { endpoint = v.replaceAll("/+$", ""); return this; }
        public Options environment(String v) { environment = v; return this; }
        public Options release(String v) { release = v; return this; }
        public Options captureRequests(boolean v) { captureRequests = v; return this; }
        public Options captureOutgoing(boolean v) { captureOutgoing = v; return this; }
        public Options sampleRate(String type, double rate) { sampleRates.put(type, rate); return this; }
        public Options ignoreRoutes(List<String> v) { ignoreRoutes = new ArrayList<>(v); return this; }
        public Options ignoreExceptions(List<String> v) { ignoreExceptions = new ArrayList<>(v); return this; }
        public Options beforeSend(Function<Map<String, Object>, Map<String, Object>> v) { beforeSend = v; return this; }
        public Options sendUserId(boolean v) { sendUserId = v; return this; }
        public Options flushIntervalMs(long v) { flushIntervalMs = v; return this; }
        public Options debug(boolean v) { debug = v; return this; }
        public Options enabled(boolean v) { enabled = v; return this; }
        /** API Trust: return the calling consumer's ID from the request yourself (overrides configured sources). */
        public Options apiTrustResolver(Function<Object, String> v) { apiTrustResolver = v; return this; }
        public Options trustProxy(boolean v) { trustProxy = v; return this; }

        private static String env(String name, String fallback) {
            String v = System.getenv(name);
            return v == null || v.isEmpty() ? fallback : v.trim();
        }

        private static String detectRelease() {
            for (String k : new String[] {"CODESKOP_RELEASE", "RENDER_GIT_COMMIT", "HEROKU_SLUG_COMMIT", "SOURCE_VERSION", "RAILWAY_GIT_COMMIT_SHA", "K_REVISION", "GITHUB_SHA"}) {
                String v = System.getenv(k);
                if (v != null && !v.isEmpty()) return v.length() > 64 ? v.substring(0, 64) : v;
            }
            return null;
        }
    }
}
