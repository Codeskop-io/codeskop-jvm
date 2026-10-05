package com.codeskop.server;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Event builders (docs/10 §10.6): exceptions, incoming requests, outgoing calls. */
public final class Events {
    private Events() {}

    static final int MAX_MESSAGE = 2048;
    static final int MAX_FRAMES = 100;
    private static final Pattern NUMERIC = Pattern.compile("^\\d+$");
    private static final Pattern UUID_RE = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern HEX = Pattern.compile("^[0-9a-fA-F]{16,}$");
    private static final Pattern PATH_PARAM = Pattern.compile("\\{([A-Za-z0-9_]+)(?::[^}]*)?}|:([A-Za-z0-9_]+)");
    private static final String[] LIBRARY_PREFIXES = {
        "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.", "kotlin.", "kotlinx.", "scala.", "org.springframework.",
        "org.apache.", "org.eclipse.jetty.", "io.undertow.", "io.netty.", "reactor.", "okhttp3.", "okio.", "com.fasterxml.",
        "org.hibernate.", "com.zaxxer.", "io.ktor.", "org.junit.", "org.gradle.", "com.codeskop.server.integrations."
    };
    /** The SDK's own classes are never app code (prefix-matched so inner/lambda classes count too). */
    private static final String[] SDK_CLASSES = {
        "com.codeskop.server.Codeskop", "com.codeskop.server.Client", "com.codeskop.server.Events", "com.codeskop.server.RequestRecorder",
        "com.codeskop.server.Trust", "com.codeskop.server.Json"
    };

    static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    /** Same templating as the server: drop the query; numeric, UUID and long-hex segments become {id}. */
    public static String normalizePath(String path) {
        String p = path == null || path.isEmpty() ? "/" : path.split("\\?", 2)[0].split("#", 2)[0];
        String[] segs = p.split("/", -1);
        for (int i = 0; i < segs.length; i++) {
            String s = segs[i];
            if (!s.isEmpty() && (NUMERIC.matcher(s).matches() || UUID_RE.matcher(s).matches() || HEX.matcher(s).matches())) segs[i] = "{id}";
        }
        String out = String.join("/", segs);
        return out.startsWith("/") ? out : "/" + out;
    }

    /** A framework route in our {name} form: Spring /users/{id:\d+} and Ktor/Express-style :id become {id}. */
    public static String templateRoute(String route) {
        if (route == null || route.isEmpty()) return "/";
        String out = PATH_PARAM.matcher(route).replaceAll(m -> "{" + (m.group(1) != null ? m.group(1) : m.group(2)) + "}");
        return out.startsWith("/") ? out : "/" + out;
    }

    static boolean inApp(String className) {
        if (className == null || className.isEmpty()) return false;
        for (String p : LIBRARY_PREFIXES) if (className.startsWith(p)) return false;
        for (String c : SDK_CLASSES) if (className.equals(c) || className.startsWith(c + "$")) return false;
        return true;
    }

    static List<Map<String, Object>> frames(StackTraceElement[] trace) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StackTraceElement el : trace) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("class", el.getClassName());
            f.put("method", el.getMethodName());
            f.put("file", el.getFileName() == null ? "" : el.getFileName());
            if (el.getLineNumber() > 0) f.put("line", el.getLineNumber());
            f.put("in_app", inApp(el.getClassName()));
            out.add(f);
            if (out.size() >= MAX_FRAMES) break;
        }
        return out; // the JVM lists the innermost frame first
    }

    private static Map<String, Object> exceptionPayload(Throwable t, int depth) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("exception_class", t.getClass().getName());
        String msg = t.getMessage() == null ? "" : t.getMessage();
        p.put("message", msg.length() > MAX_MESSAGE ? msg.substring(0, MAX_MESSAGE) : msg);
        p.put("stacktrace", frames(t.getStackTrace()));
        if (t.getCause() != null && t.getCause() != t && depth < 3) p.put("cause", exceptionPayload(t.getCause(), depth + 1));
        return p;
    }

    static Map<String, Object> event(String type, String severity, Map<String, Object> payload, String userId) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("event_id", UUID.randomUUID().toString());
        e.put("type", type);
        e.put("severity", severity);
        e.put("occurred_at", now());
        e.put("payload", payload);
        if (userId != null && !userId.isEmpty()) {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("id", userId.length() > 128 ? userId.substring(0, 128) : userId);
            e.put("user", u);
        }
        return e;
    }

    static Map<String, Object> exception(Throwable t, boolean handled, String mechanism, Map<String, Object> request, String userId, Map<String, String> tags) {
        Map<String, Object> p = exceptionPayload(t, 0);
        p.put("handled", handled);
        p.put("mechanism", mechanism);
        if (request != null) p.put("request", request);
        if (tags != null && !tags.isEmpty()) p.put("tags", new LinkedHashMap<>(tags));
        return event("exception", handled ? "medium" : "high", p, userId);
    }

    static Map<String, Object> message(String message, String severity, String userId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("exception_class", "Message");
        p.put("message", message.length() > MAX_MESSAGE ? message.substring(0, MAX_MESSAGE) : message);
        p.put("stacktrace", new ArrayList<>());
        p.put("handled", true);
        p.put("mechanism", "message");
        return event("exception", severity, p, userId);
    }

    static Map<String, Object> request(String method, String route, int status, double durationMs, Long requestBytes, Long responseBytes,
                                       String requestId, boolean failed, String userId, Map<String, Object> extra) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("method", method == null ? "GET" : method.toUpperCase());
        p.put("route", route == null ? "/" : route);
        p.put("status", status);
        p.put("duration_ms", Math.round(durationMs * 100) / 100.0);
        if (requestBytes != null && requestBytes >= 0) p.put("request_bytes", requestBytes);
        if (responseBytes != null && responseBytes >= 0) p.put("response_bytes", responseBytes);
        if (requestId != null) p.put("request_id", requestId);
        if (extra != null) p.putAll(extra);
        return event("http_request", failed || status >= 500 ? "high" : "low", p, userId);
    }

    static List<Map<String, Object>> outgoing(String method, String host, String path, Integer status, double durationMs, String errorKind, String userId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("method", method == null ? "GET" : method.toUpperCase());
        p.put("host", host);
        p.put("path", normalizePath(path));
        p.put("duration_ms", Math.round(durationMs * 100) / 100.0);
        if (status != null) p.put("status", status);
        String kind = errorKind;
        if (kind == null && status != null && status >= 400) kind = status >= 500 ? "http_5xx" : "http_4xx";
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(event("api_timing", "low", new LinkedHashMap<>(p), userId));
        if (kind != null) {
            Map<String, Object> err = new LinkedHashMap<>(p);
            err.put("error_kind", kind);
            out.add(event("api_error", "high", err, userId));
        }
        return out;
    }
}
