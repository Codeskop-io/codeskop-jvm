<p align="center">
  <a href="https://www.codeskop.com/?utm_source=github&utm_medium=readme&utm_campaign=codeskop-jvm">
    <img src="https://www.codeskop.com/media/product-tour-poster.jpg" alt="Codeskop dashboard: crash-free users, active users, open issues and API latency" width="720">
  </a>
</p>

# Codeskop for the JVM (Java and Kotlin)

**Codeskop** is crash, error and API monitoring for mobile, web and backend apps. [Get started free at www.codeskop.com](https://www.codeskop.com/?utm_source=github&utm_medium=readme&utm_campaign=codeskop-jvm).

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fcom%2Fcodeskop%2Fsdk%2Fcodeskop-server%2Fmaven-metadata.xml&label=maven%20central)](https://central.sonatype.com/artifact/com.codeskop.sdk/codeskop-server)
[![Java version](https://img.shields.io/badge/java-11%2B-blue)](https://github.com/Codeskop-io/codeskop-jvm/blob/main/build.gradle.kts)
[![license](https://img.shields.io/github/license/Codeskop-io/codeskop-jvm.svg)](https://github.com/Codeskop-io/codeskop-jvm/blob/main/LICENSE)

`codeskop-server` is the JVM SDK for [Codeskop](https://www.codeskop.com/?utm_source=github&utm_medium=readme&utm_campaign=codeskop-jvm). Add it to a Spring Boot or Servlet app, in Java or Kotlin, and Codeskop groups its errors into issues with stack traces, records every request it handles by route, and shows which outgoing API calls fail or slow down, next to the crashes and network calls from your mobile and web apps.

- **Errors:** uncaught exceptions on any thread, unhandled exceptions in requests (with their route), and `Codeskop.captureException()` / `captureMessage()`. Cause chains included.
- **Incoming requests:** every request your service handles, by route pattern (`/orders/{id}`), with status, latency and size.
- **Outgoing calls:** OkHttp and `java.net.http.HttpClient` calls to other APIs, with status, latency and failures.
- **Light and safe:** no runtime dependencies; the Servlet and OkHttp integrations switch on only when those libraries are on your classpath. Events are batched and sent from a background thread, and flushed before the JVM exits.

Works with Spring Boot 3 and any Jakarta Servlet container (Tomcat, Jetty, Undertow), OkHttp and `java.net.http.HttpClient`. Java 11 and later, and Kotlin.

## Get started in 2 minutes

**1. Create a free account** at **[dashboard.codeskop.com/signup](https://dashboard.codeskop.com/signup?utm_source=github&utm_medium=readme&utm_campaign=codeskop-jvm)**. No credit card. Every project has a **Test** key (`cs_test_pk_…`) and a **Live** key (`cs_live_pk_…`); copy one from **API tokens**.

**2. Add the dependency:**

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.codeskop.sdk:codeskop-server:0.1.0")
}
```

Maven:

```xml
<dependency>
  <groupId>com.codeskop.sdk</groupId>
  <artifactId>codeskop-server</artifactId>
  <version>0.1.0</version>
</dependency>
```

**3. Set your key** (Test key locally, Live key in production):

```bash
export CODESKOP_API_KEY=cs_test_pk_…
```

**4. Initialize once at startup and register the filter** (Spring Boot):

```java
import com.codeskop.server.Codeskop;
import com.codeskop.server.integrations.CodeskopFilter;

@SpringBootApplication
public class ShopApplication {
    public static void main(String[] args) {
        Codeskop.init(Codeskop.options());   // reads CODESKOP_API_KEY; or .apiKey("cs_live_pk_…")
        SpringApplication.run(ShopApplication.class, args);
    }

    @Bean
    CodeskopFilter codeskopFilter() {
        return new CodeskopFilter();
    }
}
```

Throw an exception in a controller and it shows up in your dashboard a few seconds later.

## Frameworks

### Spring Boot and Spring MVC

Register `CodeskopFilter` as a bean, as above. Requests are recorded by route pattern (`/orders/{id}`), unhandled exceptions are reported with their route, and the authenticated principal's name is attached as the user ID. In Kotlin:

```kotlin
@Bean
fun codeskopFilter() = CodeskopFilter()
```

### Any Jakarta Servlet container

Outside Spring, add `com.codeskop.server.integrations.CodeskopFilter` to your container (`web.xml` or `ServletContext.addFilter`) for all paths.

### Outgoing calls

```java
import com.codeskop.server.integrations.CodeskopHttp;
import com.codeskop.server.integrations.CodeskopOkHttpInterceptor;

// OkHttp
OkHttpClient client = new OkHttpClient.Builder()
    .addInterceptor(new CodeskopOkHttpInterceptor())
    .build();

// java.net.http.HttpClient
HttpResponse<String> res = CodeskopHttp.send(httpClient, request, HttpResponse.BodyHandlers.ofString());
```

## Common tasks

```java
// Report a handled error, with optional tags.
try {
    payments.charge(order);
} catch (PaymentException e) {
    Codeskop.captureException(e, Map.of("provider", "stripe"));
}

Codeskop.captureMessage("Inventory sync skipped");

// Inside a request: attach events to a user (your own ID, never an email).
Codeskop.setUser(user.getId());
```

## What's captured automatically

- **Uncaught exceptions on any thread**, flushed before the JVM exits.
- **Unhandled exceptions in requests**, with the route they happened on, once `CodeskopFilter` is registered.
- **Every incoming request** by route, with method, status, latency and size. Find them on the Network page under *Requests your servers receive*. Health checks (`/health*`, `/healthz`, `/metrics`, `/favicon.ico`) and `/actuator/*` aren't recorded.
- **Outgoing calls** through `CodeskopOkHttpInterceptor` or `CodeskopHttp.send`, on the Network page under *APIs your apps call*.
- **Users**: the authenticated principal's name.
- **Release**, detected from `CODESKOP_RELEASE` or the commit on Render, Heroku, Railway, Cloud Run and GitHub Actions.

## API keys and environments

Server SDKs use the project's **public** key, the same kind of key your mobile and web apps use. Public keys can only send events, never read your data.

| Key | Use it for |
|---|---|
| `cs_test_pk_…` (Test) | Local development, CI and staging, so test traffic never mixes with real users |
| `cs_live_pk_…` (Live) | Production |

Set it as `CODESKOP_API_KEY` in each environment and the SDK reads it on its own, or pass `Codeskop.options().apiKey(…)` in code. Never put a secret key (`cs_…_sk_…`) in a service: the SDK refuses it and stays switched off, without throwing. A missing or malformed key does the same, so if nothing shows up, check the key first.

Create a separate Codeskop project for each backend service so its issues and routes stay apart. More: [Core concepts](https://www.codeskop.com/docs/concepts?utm_source=github&utm_medium=readme&utm_campaign=codeskop-jvm) and [server SDK keys](https://www.codeskop.com/docs/server?utm_source=github&utm_medium=readme&utm_campaign=codeskop-jvm#keys).

## Configuration

Options are set on the builder from `Codeskop.options()`, for example `Codeskop.init(Codeskop.options().environment("staging").debug(true))`.

| Option | Type | Default | Description |
|---|---|---|---|
| `apiKey` | `String` | `CODESKOP_API_KEY` | The project's public key. Secret keys are refused and the SDK stays off. |
| `environment` | `String` | `"production"` | Shown on events. Env var `CODESKOP_ENVIRONMENT`. |
| `release` | `String` | auto | Your version or commit. Env var `CODESKOP_RELEASE`. |
| `captureRequests` | `boolean` | `true` | Record incoming requests. |
| `captureOutgoing` | `boolean` | `true` | Record outgoing calls. |
| `ignoreRoutes` | `List<String>` | health checks, `/actuator/*` | Glob patterns never recorded. |
| `ignoreExceptions` | `List<String>` | empty | Exception class names never sent. |
| `beforeSend` | `Function` | none | Edit or drop any event before it's sent. |
| `sendUserId` | `boolean` | `true` | Attach the authenticated principal's name. |
| `debug` | `boolean` | `false` | Log what the SDK does. |

## Plans

| | Free | Starter | Growth | Scale |
|---|---|---|---|---|
| Errors | ✓ | ✓ | ✓ | ✓ |
| Incoming requests and outgoing API calls | | ✓ | ✓ | ✓ |
| Events per month | 10,000 | 100,000 | 1,000,000 | 10,000,000 |
| Data retention | 7 days | 30 days | 90 days | 365 days |

The SDK is the same on every plan; your plan decides what's kept. Request and API call monitoring follow the network monitoring on your plan, with the same sampling, and failed requests are always kept. See [pricing](https://www.codeskop.com/pricing?utm_source=github&utm_medium=readme&utm_campaign=codeskop-jvm).

## Privacy

**Never captured:** request or response bodies, cookies, `Authorization` headers and query strings. Requests are recorded by route template, so IDs in the path don't become separate routes.

**What is sent:** the error type, message and stack trace; for each request, the method, route, status, latency and size; for outgoing calls, the method, host, path, status and latency. Each event also carries your environment and release, plus the host name, OS and runtime version of the server.

**You choose the user ID.** Pass your own ID, never an email address or phone number.

**Error messages are sent as written.** If yours can contain personal data, scrub or drop events with `beforeSend`, or skip error types with `ignoreExceptions`.

Details: [Privacy & Security](https://www.codeskop.com/docs/privacy?utm_source=github&utm_medium=readme&utm_campaign=codeskop-jvm).

## Documentation

- [Java and Kotlin guide](https://www.codeskop.com/docs/server/java?utm_source=github&utm_medium=readme&utm_campaign=codeskop-jvm)
- [Server SDKs overview](https://www.codeskop.com/docs/server?utm_source=github&utm_medium=readme&utm_campaign=codeskop-jvm)
- [Privacy & Security](https://www.codeskop.com/docs/privacy?utm_source=github&utm_medium=readme&utm_campaign=codeskop-jvm)
- [Troubleshooting](https://www.codeskop.com/docs/troubleshooting?utm_source=github&utm_medium=readme&utm_campaign=codeskop-jvm)
- [Changelog](https://github.com/Codeskop-io/codeskop-jvm/blob/main/CHANGELOG.md)

## Other Codeskop SDKs

Monitor your mobile apps, web apps and backend services in one dashboard:

| Platform | Package | Links |
|---|---|---|
| Android | `com.codeskop.sdk:tracker-android` | [Docs](https://www.codeskop.com/docs/quickstart/android?utm_source=github&utm_medium=readme&utm_campaign=codeskop-jvm) |
| Kotlin Multiplatform and iOS (beta) | `com.codeskop.sdk:tracker-core` | [Docs](https://www.codeskop.com/docs/kmp?utm_source=github&utm_medium=readme&utm_campaign=codeskop-jvm) |
| Flutter | `codeskop_tracker` | [Docs](https://www.codeskop.com/docs/android/flutter?utm_source=github&utm_medium=readme&utm_campaign=codeskop-jvm) |
| Web (JavaScript, React) | `@codeskop/tracker`, `@codeskop/tracker-react` | [Docs](https://www.codeskop.com/docs/web/installation?utm_source=github&utm_medium=readme&utm_campaign=codeskop-jvm) · [GitHub](https://github.com/Codeskop-io/web-sdk) |
| Go | `github.com/Codeskop-io/codeskop-go` | [Docs](https://www.codeskop.com/docs/server/go?utm_source=github&utm_medium=readme&utm_campaign=codeskop-jvm) · [GitHub](https://github.com/Codeskop-io/codeskop-go) |
| Python | `codeskop` | [Docs](https://www.codeskop.com/docs/server/python?utm_source=github&utm_medium=readme&utm_campaign=codeskop-jvm) · [GitHub](https://github.com/Codeskop-io/codeskop-python) |
| Node.js | `@codeskop/node` | [Docs](https://www.codeskop.com/docs/server/node?utm_source=github&utm_medium=readme&utm_campaign=codeskop-jvm) · [GitHub](https://github.com/Codeskop-io/codeskop-node) |
| **Java and Kotlin (JVM)** (this SDK) | `com.codeskop.sdk:codeskop-server` | [Docs](https://www.codeskop.com/docs/server/java?utm_source=github&utm_medium=readme&utm_campaign=codeskop-jvm) · [GitHub](https://github.com/Codeskop-io/codeskop-jvm) |
| PHP | `codeskop/codeskop-php` | [Docs](https://www.codeskop.com/docs/server/php?utm_source=github&utm_medium=readme&utm_campaign=codeskop-jvm) · [GitHub](https://github.com/Codeskop-io/codeskop-php) |
| Any other language | OpenTelemetry (OTLP) or HTTPS | [OpenTelemetry](https://www.codeskop.com/docs/server/opentelemetry?utm_source=github&utm_medium=readme&utm_campaign=codeskop-jvm) · [Ingest API](https://www.codeskop.com/docs/reference/ingest-api?utm_source=github&utm_medium=readme&utm_campaign=codeskop-jvm) |

## Help

Questions or a bug? [Contact us](https://www.codeskop.com/contact?utm_source=github&utm_medium=readme&utm_campaign=codeskop-jvm), email support@codeskop.com, or open an issue in this repository.

## Development

```bash
./gradlew test
```

Tests run against a local mock of the ingest API, including a real Spring Boot app on Tomcat and Jetty. Releases: see [RELEASING.md](https://github.com/Codeskop-io/codeskop-jvm/blob/main/RELEASING.md).

## License

[MIT](https://github.com/Codeskop-io/codeskop-jvm/blob/main/LICENSE)
