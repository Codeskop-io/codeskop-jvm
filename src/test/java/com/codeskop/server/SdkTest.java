package com.codeskop.server;

import static com.codeskop.server.MockIngest.KEY;
import static com.codeskop.server.MockIngest.payload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.codeskop.server.integrations.CodeskopFilter;
import com.codeskop.server.integrations.CodeskopHttp;
import com.codeskop.server.integrations.CodeskopOkHttpInterceptor;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import okhttp3.OkHttpClient;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SdkTest {
    MockIngest ingest;
    MockIngest upstream;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        ingest = new MockIngest();
        upstream = new MockIngest();
        upstream.routes.put("/v1/charges/err", new Object[] {502, Map.of("detail", "bad gateway")});
    }

    @AfterEach
    void tearDown() {
        Codeskop.close(500);
        Codeskop.client = null;
        ingest.close();
        upstream.close();
    }

    Client start(Codeskop.Options o) throws Exception {
        Client c = Codeskop.init(o.apiKey(KEY).endpoint(ingest.url).flushIntervalMs(50));
        for (int i = 0; i < 100 && !ingest.requests.contains("GET /v1/config"); i++) Thread.sleep(20);
        Thread.sleep(50);
        return c;
    }

    static void boom() { throw new IllegalArgumentException("order total can't be negative"); }

    @Test
    void secretKeyDisablesWithoutThrowing() {
        assertFalse(Codeskop.init(Codeskop.options().apiKey("cs_live_sk_supersecret123").endpoint(ingest.url)).enabled);
        Codeskop.captureMessage("ignored");
        assertTrue(Codeskop.flush(200));
        assertTrue(ingest.events().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void exceptionWithFramesCauseAndContext() throws Exception {
        start(Codeskop.options().release("1.4.2").environment("test"));
        try {
            try { boom(); } catch (RuntimeException e) { throw new IllegalStateException("checkout failed", e); }
        } catch (RuntimeException e) {
            Codeskop.captureException(e, Map.of("area", "checkout"));
        }
        assertTrue(Codeskop.flush(3000));
        Map<String, Object> p = payload(ingest.ofType("exception").get(0));
        assertEquals("java.lang.IllegalStateException", p.get("exception_class"));
        List<Map<String, Object>> frames = (List<Map<String, Object>>) p.get("stacktrace");
        assertEquals("com.codeskop.server.SdkTest", frames.get(0).get("class"));
        assertEquals(true, frames.get(0).get("in_app"));
        Map<String, Object> cause = (Map<String, Object>) p.get("cause");
        assertEquals("boom", ((List<Map<String, Object>>) cause.get("stacktrace")).get(0).get("method"));
        assertTrue(frames.stream().anyMatch(f -> Boolean.FALSE.equals(f.get("in_app")))); // junit / jdk frames
        Map<String, Object> ctx = (Map<String, Object>) ingest.batches.get(0).get("context");
        assertEquals("jvm", ((Map<String, Object>) ctx.get("device")).get("platform"));
        assertEquals("codeskop-jvm", ((Map<String, Object>) ctx.get("app")).get("sdk_name"));
    }

    @Test
    void retriesThenDelivers() throws Exception {
        start(Codeskop.options());
        ingest.responses.add(new int[] {429, 0});
        ingest.responses.add(new int[] {503, 0});
        Codeskop.captureMessage("eventually delivered");
        assertTrue(Codeskop.flush(5000));
        assertEquals(1, ingest.ofType("exception").size());
        assertEquals(3, ingest.requests.stream().filter(r -> r.startsWith("POST")).count());
    }

    @Test
    void batchesOfAtMost100() throws Exception {
        start(Codeskop.options());
        for (int i = 0; i < 250; i++) Codeskop.captureMessage("m" + i);
        assertTrue(Codeskop.flush(5000));
        List<Integer> sizes = ingest.batches.stream().map(b -> ((List<?>) b.get("batch")).size()).collect(Collectors.toList());
        assertTrue(sizes.stream().allMatch(s -> s <= 100));
        assertEquals(250, sizes.stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void killSwitch() throws Exception {
        ingest.config = Map.of("enabled", false);
        start(Codeskop.options());
        Codeskop.captureMessage("not sent");
        Codeskop.flush(300);
        assertTrue(ingest.events().isEmpty());
    }

    @Test
    void uncaughtThreadException() throws Exception {
        start(Codeskop.options());
        Thread t = new Thread(SdkTest::boom);
        t.start();
        t.join();
        for (int i = 0; i < 100 && ingest.ofType("exception").isEmpty(); i++) Thread.sleep(20);
        Map<String, Object> p = payload(ingest.ofType("exception").get(0));
        assertEquals("uncaught", p.get("mechanism"));
        assertEquals(false, p.get("handled"));
    }

    @Test
    void routeHelpers() {
        assertEquals("/orders/{id}/items/{itemId}", Events.templateRoute("/orders/{id:\\d+}/items/{itemId}"));
        assertEquals("/users/{id}", Events.templateRoute("/users/:id"));
        assertEquals("/users/{id}/files/{id}", Events.normalizePath("/users/42/files/0f8fad5b-d9cb-469f-a165-70867728950e?x=1"));
    }

    // -- servlet (Jetty) -------------------------------------------------------------

    Server jetty() throws Exception {
        Server server = new Server(0);
        ServletContextHandler ctx = new ServletContextHandler();
        ctx.addFilter(new FilterHolder(new CodeskopFilter()), "/*", EnumSet.of(jakarta.servlet.DispatcherType.REQUEST));
        ctx.addServlet(new ServletHolder(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res) throws java.io.IOException {
                if (req.getRequestURI().startsWith("/boom")) throw new IllegalStateException("servlet exploded");
                Codeskop.setUser("u-" + req.getRequestURI().length());
                res.setStatus(req.getRequestURI().startsWith("/missing") ? 404 : 200);
                res.getWriter().write("ok");
            }
        }), "/*");
        server.setHandler(ctx);
        server.start();
        return server;
    }

    int get(String url, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
        headers.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void servletFilterRecordsRequestsAndErrors() throws Exception {
        start(Codeskop.options().captureOutgoing(false));
        Server server = jetty();
        String base = "http://127.0.0.1:" + server.getURI().getPort();
        try {
            assertEquals(200, get(base + "/orders/42", Map.of()));
            assertEquals(500, get(base + "/boom", Map.of()));
            get(base + "/healthz", Map.of());
        } finally {
            server.stop();
        }
        Codeskop.flush(3000);
        List<String> reqs = ingest.ofType("http_request").stream().map(e -> payload(e).get("route") + " " + payload(e).get("status")).sorted().collect(Collectors.toList());
        assertEquals(List.of("/boom 500", "/orders/{id} 200"), reqs);
        assertEquals("servlet", payload(ingest.ofType("exception").get(0)).get("mechanism"));
        assertEquals(Map.of("id", "u-10"), ingest.ofType("http_request").stream().filter(e -> payload(e).get("status").equals(200L)).findFirst().get().get("user"));
    }

    // -- outgoing ------------------------------------------------------------------------

    @Test
    void okHttpAndJavaHttpClientOutgoing() throws Exception {
        start(Codeskop.options());
        String target = upstream.url.replace("127.0.0.1", "localhost");
        OkHttpClient ok = new OkHttpClient.Builder().addInterceptor(new CodeskopOkHttpInterceptor()).build();
        ok.newCall(new okhttp3.Request.Builder().url(target + "/v1/customers/123?expand=1").build()).execute().close();
        ok.newCall(new okhttp3.Request.Builder().url(target + "/v1/charges/err").build()).execute().close();
        CodeskopHttp.send(http, HttpRequest.newBuilder(URI.create(target + "/v1/legacy/9")).build(), HttpResponse.BodyHandlers.discarding());
        Codeskop.flush(3000);
        List<String> timings = ingest.ofType("api_timing").stream().map(e -> payload(e).get("path") + " " + payload(e).get("status")).sorted().collect(Collectors.toList());
        assertEquals(List.of("/v1/charges/err 502", "/v1/customers/{id} 200", "/v1/legacy/{id} 200"), timings);
        assertEquals("http_5xx", payload(ingest.ofType("api_error").get(0)).get("error_kind"));
    }

    // -- API Trust --------------------------------------------------------------------------

    static final String SALT = "s".repeat(64);

    static String h(String raw) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SALT.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder sb = new StringBuilder();
        for (byte b : mac.doFinal(raw.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
        return sb.substring(0, 32);
    }

    static Map<String, Object> trustConfig(boolean blocking) {
        return Map.of("enabled", true, "features", Map.of("network", true), "sample_rates", Map.of("http_request", 0.0),
            "api_trust", Map.of("enabled", true, "salt", SALT, "trust_proxy", true, "blocking", blocking, "verdicts_path", "/v1/trust/verdicts",
                "consumer_sources", List.of(Map.of("type", "header", "name", "X-API-Key"),
                    Map.of("type", "jwt", "header", "Authorization", "claims", List.of("client_id", "sub")),
                    Map.of("type", "mtls", "header", "X-Client-Cert"), Map.of("type", "query", "name", "api_key"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void trustCaptureAndBlocking() throws Exception {
        ingest.config = trustConfig(true);
        ingest.routes.put("/v1/trust/verdicts", new Object[] {200, Map.of("blocked", List.of(h("banned-key")))});
        start(Codeskop.options().captureOutgoing(false));
        Server server = jetty();
        String base = "http://127.0.0.1:" + server.getURI().getPort();
        String jwt = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"RS256\"}".getBytes()) + "."
            + Base64.getUrlEncoder().withoutPadding().encodeToString("{\"client_id\":\"partner-42\"}".getBytes()) + ".sig";
        try {
            get(base + "/v1/charges", Map.of("X-API-Key", "live_secret_123", "X-Forwarded-For", "203.0.113.7, 10.0.0.1", "Origin", "https://shop.example.com"));
            get(base + "/v1/charges", Map.of("Authorization", "Bearer " + jwt));
            get(base + "/v1/charges", Map.of("X-Client-Cert", "sha256:ab12"));
            get(base + "/v1/charges?api_key=qkey", Map.of());
            assertEquals(403, get(base + "/v1/charges", Map.of("X-API-Key", "banned-key")));
            get(base + "/v1/charges", Map.of()); // no consumer: sampled out
        } finally {
            server.stop();
        }
        Codeskop.flush(3000);
        List<Map<String, Object>> reqs = ingest.ofType("http_request");
        List<String> got = reqs.stream().map(e -> {
            Map<String, Object> c = (Map<String, Object>) payload(e).get("consumer");
            return c.get("auth_type") + ":" + c.get("id_hash") + (Boolean.TRUE.equals(payload(e).get("blocked")) ? ":blocked" : "");
        }).collect(Collectors.toList());
        assertEquals(List.of("api_key:" + h("live_secret_123"), "jwt:" + h("partner-42"), "mtls:" + h("sha256:ab12"), "api_key:" + h("qkey"),
            "api_key:" + h("banned-key") + ":blocked"), got);
        Map<String, Object> client = (Map<String, Object>) payload(reqs.get(0)).get("client");
        assertEquals("203.0.113.7", client.get("ip"));
        assertFalse(Json.write(ingest.events()).contains("live_secret_123"));
    }
}
