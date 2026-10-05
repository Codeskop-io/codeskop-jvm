package com.codeskop.server;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** One incoming request: start, (optional) exception, finish. Used by every framework integration. */
public final class RequestRecorder {
    public static final String BLOCKED_BODY = "{\"error\":\"consumer_blocked\"}";
    private final String method;
    private final String requestId;
    private final long started = System.nanoTime();
    private boolean failed;
    private boolean blocked;
    private boolean finished;
    private Map<String, Object> trustExtra;

    public RequestRecorder(String method, Function<String, String> header) {
        this.method = method;
        String id = header.apply("x-request-id");
        this.requestId = id != null && !id.isEmpty() ? (id.length() > 128 ? id.substring(0, 128) : id) : UUID.randomUUID().toString().replace("-", "");
        Codeskop.clearUser();
    }

    /** API Trust capture; true when the request must be refused (opt-in blocking, fails open). */
    public boolean trust(Object request, Function<String, String> header, Function<String, String> query, String peer) {
        Client c = Codeskop.client;
        Trust t = c == null ? null : c.trust;
        if (t == null || !t.active) return false;
        try {
            Trust.Inspection i = t.inspect(request, header, query, peer);
            trustExtra = i.extra;
            blocked = i.blocked;
            return blocked;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void exception(Throwable error, String route, String mechanism) {
        failed = true;
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("method", method);
        req.put("route", route);
        req.put("request_id", requestId);
        Codeskop.captureException(error, null, false, mechanism, req);
    }

    public void finish(String route, int status, Long requestBytes, Long responseBytes) {
        if (finished) return;
        finished = true;
        Client c = Codeskop.client;
        try {
            if (c == null || !c.enabled || !c.options.captureRequests || c.ignoredRoute(route)) return;
            Map<String, Object> extra = new LinkedHashMap<>();
            if (trustExtra != null) extra.putAll(trustExtra);
            if (blocked) extra.put("blocked", true);
            c.capture(Events.request(method, route, status, (System.nanoTime() - started) / 1e6, requestBytes, responseBytes,
                requestId, failed, Codeskop.currentUser(), extra.isEmpty() ? null : extra));
        } finally {
            Codeskop.clearUser();
        }
    }
}
