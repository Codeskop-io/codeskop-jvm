# Codeskop for the JVM (Java and Kotlin)

Errors, incoming requests and outgoing API calls from your JVM backend, in Codeskop. Java 11+, zero runtime dependencies.

```kotlin
implementation("com.codeskop.sdk:codeskop-server:0.1.0")
```

```java
Codeskop.init(Codeskop.options().apiKey(System.getenv("CODESKOP_API_KEY"))); // cs_live_pk_… (public key)
```

## Spring Boot 3 / Spring MVC / any Jakarta Servlet container

```java
@Bean
CodeskopFilter codeskopFilter() { return new CodeskopFilter(); }
```

Requests are recorded by route pattern (`/orders/{id}`); unhandled exceptions are reported with the route; the authenticated principal's name is attached as the user ID (or call `Codeskop.setUser(id)`).

## Outgoing calls

```java
OkHttpClient client = new OkHttpClient.Builder().addInterceptor(new CodeskopOkHttpInterceptor()).build();
HttpResponse<String> r = CodeskopHttp.send(httpClient, request, HttpResponse.BodyHandlers.ofString());
```

## Errors

```java
Codeskop.captureException(e, Map.of("provider", "stripe"));
Codeskop.captureMessage("Inventory sync skipped");
```

Uncaught exceptions on any thread are reported and flushed before the JVM exits.

## Options

`apiKey`, `endpoint`, `environment`, `release` (auto-detected from common CI/host variables), `captureRequests`, `captureOutgoing`, `ignoreRoutes` (health checks and `/actuator/*` by default), `ignoreExceptions`, `beforeSend`, `sendUserId`, `debug`. Environment variables: `CODESKOP_API_KEY`, `CODESKOP_ENDPOINT`, `CODESKOP_ENVIRONMENT`, `CODESKOP_RELEASE`.

Never captured: request or response bodies, cookies, `Authorization` headers, query strings.

## Development

```bash
./gradlew test
```

Tests run against a local mock of the ingest API, including a real Spring Boot app on Tomcat and Jetty. Contract: backend repo `docs/10-server-sdk-spec.md`.

## License

MIT
