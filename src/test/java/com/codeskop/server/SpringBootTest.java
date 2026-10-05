package com.codeskop.server;

import static com.codeskop.server.MockIngest.KEY;
import static com.codeskop.server.MockIngest.payload;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.codeskop.server.integrations.CodeskopFilter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** A real Spring Boot 3 app on an embedded Tomcat, with the filter registered as a bean. */
class SpringBootTest {
    @SpringBootApplication
    @RestController
    static class App {
        @Bean
        CodeskopFilter codeskopFilter() { return new CodeskopFilter(); }

        @GetMapping("/orders/{orderId}")
        String order(@PathVariable("orderId") String orderId) { return orderId; }

        @GetMapping("/boom")
        String boom() { throw new UnsupportedOperationException("spring exploded"); }
    }

    @Test
    void routePatternsAndErrors() throws Exception {
        try (MockIngest ingest = new MockIngest()) {
            Codeskop.init(Codeskop.options().apiKey(KEY).endpoint(ingest.url).flushIntervalMs(50).captureOutgoing(false));
            ConfigurableApplicationContext ctx = SpringApplication.run(App.class, "--server.port=0", "--spring.main.banner-mode=off",
                "--logging.level.root=WARN");
            try {
                int port = ((WebServerApplicationContext) ctx).getWebServer().getPort();
                HttpClient http = HttpClient.newHttpClient();
                for (String p : List.of("/orders/7", "/orders/8", "/boom")) {
                    http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + p)).build(), HttpResponse.BodyHandlers.discarding());
                }
                Codeskop.flush(3000);
                List<String> reqs = ingest.ofType("http_request").stream()
                    .filter(e -> !"/error".equals(payload(e).get("route")))
                    .map(e -> payload(e).get("route") + " " + payload(e).get("status")).sorted().collect(Collectors.toList());
                assertEquals(List.of("/boom 500", "/orders/{orderId} 200", "/orders/{orderId} 200"), reqs);
                assertEquals(List.of("java.lang.UnsupportedOperationException"),
                    ingest.ofType("exception").stream().map(e -> payload(e).get("exception_class")).collect(Collectors.toList()));
            } finally {
                ctx.close();
                Codeskop.close(500);
                Codeskop.client = null;
            }
        }
    }
}
