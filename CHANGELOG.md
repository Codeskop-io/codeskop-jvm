# Changelog

## 0.1.1, 2026-10-06

- POM developer contact: support@codeskop.com.

## 0.1.0 (beta), 2026-10-05

First release.

- Errors: uncaught exceptions on any thread (flushed before the JVM exits), `Codeskop.captureException`, `captureMessage`, cause chains, frames marked `in_app`.
- Incoming requests (`http_request`) through `CodeskopFilter` (Jakarta Servlet: Spring Boot 3 / Spring MVC route patterns, Tomcat, Jetty).
- Outgoing calls through `CodeskopOkHttpInterceptor` (OkHttp / Retrofit) and `CodeskopHttp.send` (`java.net.http`).
- Background sending in gzip batches of up to 100 with Retry-After and backoff.
- Remote config, sampling (failures and API Trust calls are never sampled out), API Trust capture and opt-in blocking.
- Zero runtime dependencies; runs on Java 11+.
