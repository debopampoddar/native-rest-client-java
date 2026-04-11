# Native REST Client for Java

A lightweight, minimal-dependency declarative HTTP client built exclusively for Java 21+.

Designed as a modern, native alternative to Retrofit and Feign, this library allows you to
translate REST APIs into Java interfaces using annotations. By stripping away third-party
networking engines like OkHttp, it relies entirely on the JDK's built-in
[`java.net.http.HttpClient`](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html)
and is fully compatible with virtual threads.

Whether you are building microservices or desktop applications, this library provides a clean,
familiar developer experience fully optimised for modern Java.

---

## ✨ Key Features

- **Zero Networking Dependencies**
  Uses the JDK's `java.net.http.HttpClient` — no OkHttp or Netty required.

- **Declarative API Interfaces**
  Define your HTTP API as annotated Java interfaces:
  `@GET`, `@POST`, `@PUT`, `@DELETE`, `@PATCH`, `@Path`, `@Query`, `@QueryMap`,
  `@Body`, `@Url`, `@Header`, `@HeaderMap`, `@Headers`, `@Field`, `@FormUrlEncoded`.

- **Sync & Async Execution**
  - Synchronous methods returning plain types (`User`, `List<User>`, `String`, `InputStream`, `void`).
  - Asynchronous methods returning `CompletableFuture<T>`.

- **Two-Stage Interceptor Pipeline**
  - **Stage 1 — `ClientInterceptor`**: request-only transformations (auth headers, static headers).
  - **Stage 2 — `HttpExchangeInterceptor`**: around-call wrappers (metrics, retry, token refresh, response logging).

- **Pluggable Message Converters**
  Built-in: `StringConverter` (raw text) and `JacksonConverter` (JSON).
  Extendable via `NativeRestClient.Builder#addConverter` (XML, Protobuf, CSV, etc.).

- **Response Envelope (Optional)**
  Use `HttpResponseEnvelope<T>` to access HTTP status and headers on both successful
  and error responses without throwing `ApiException`.

- **Form URL Encoding**
  `@FormUrlEncoded` + `@Field` for `application/x-www-form-urlencoded` bodies.

- **Auth Helpers**
  `BasicAuthInterceptor`, `BearerAuthInterceptor`, and full OAuth 2.0 token-refresh
  infrastructure (`TokenFetcher`, `AccessToken`, `RefreshingTokenManager`, `OAuthInterceptor`).

- **Micrometer Metrics**
  `MicrometerMetricsRecorder` + `MetricsExchangeInterceptor` emit dimensional latency
  metrics compatible with Prometheus, Datadog, and any other Micrometer backend.

- **Virtual Thread Ready**
  Pass `Executors.newVirtualThreadPerTaskExecutor()` to the builder for effortless
  high-concurrency without platform-thread tuning.

---

## Maven Coordinates

```xml
<dependency>
  <groupId>io.declarative.http</groupId>
  <artifactId>native-rest-client</artifactId>
  <version>1.0.0</version>
</dependency>
```

Requires Java 21+.

---

## Quick Start

### 1. Define your API interface

```java
public interface UserService {

    @GET("/users/{id}")
    User getUser(@Path("id") long id);

    @GET("/users/{id}")
    CompletableFuture<User> getUserAsync(@Path("id") long id);

    @GET("/users")
    List<User> listUsers(
            @Query("page") int page,
            @Query("size") int size,
            @Query("sort") String sort
    );
}
```

### 2. Build a client

```java
NativeRestClient client = NativeRestClient.builder("https://api.example.com")
        .build();

UserService api = client.create(UserService.class);

// Synchronous
User user = api.getUser(42L);

// Asynchronous with timeout
User asyncUser = api.getUserAsync(42L)
        .orTimeout(2, TimeUnit.SECONDS)
        .join();
```

### 3. Using virtual threads (recommended for high concurrency)

```java
NativeRestClient client = NativeRestClient.builder("https://api.example.com")
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .build();
```

---

## Annotations Reference

| Annotation | Target | Description |
|---|---|---|
| `@GET(path)` | method | HTTP GET |
| `@POST(path)` | method | HTTP POST |
| `@PUT(path)` | method | HTTP PUT |
| `@DELETE(path)` | method | HTTP DELETE |
| `@PATCH(path)` | method | HTTP PATCH |
| `@Headers({"K: V"})` | method | Static headers added to every call of this method |
| `@FormUrlEncoded` | method | Encodes `@Field` params as `application/x-www-form-urlencoded` |
| `@Path("name")` | parameter | URL path segment replacement (`{name}`) — null values throw `IllegalArgumentException` |
| `@Query("name")` | parameter | Appends `?name=value` to the URL; null values are omitted |
| `@QueryMap` | parameter | Appends all `Map<String, ?>` entries as query parameters |
| `@Header("name")` | parameter | Sets a single request header per call |
| `@HeaderMap` | parameter | Merges all `Map<String, String>` entries as request headers |
| `@Body` | parameter | Serialises the parameter as the request body (JSON by default) |
| `@Url` | parameter | Overrides the full request URL (ignores the path in `@GET` etc.) |
| `@Field("name")` | parameter | One form field; requires `@FormUrlEncoded` on the method |

---

## Interceptor Pipeline

When a proxy method is invoked, requests flow through two sequential stages before reaching
```
HttpClient.send(..)`:
Method call
│
▼
[Stage 1 — ClientInterceptor chain]
· modifies the outgoing HttpRequest only
· examples: LoggingInterceptor, BasicAuthInterceptor, BearerAuthInterceptor
│
▼
[Stage 2 — HttpExchangeInterceptor chain]
· wraps HttpClient.send(..) — sees both HttpRequest and HttpResponse
· examples: MetricsExchangeInterceptor, RetryOnServerErrorInterceptor,
TokenRefreshExchangeInterceptor, ResponseLoggingExchangeInterceptor
│
▼
HttpClient.send(request)
│
▼
[ResponseConverter chain]
· StringConverter → JacksonConverter → custom converters
│
▼
T / CompletableFuture<T> / HttpResponseEnvelope<T>
```


---

## Stage 1 — Request Interceptors (`ClientInterceptor`)

Implement `ClientInterceptor` to inspect or modify the outgoing `HttpRequest` before
any network I/O takes place.

```java
public final class LoggingInterceptor implements ClientInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoggingInterceptor.class);

    @Override
    public HttpRequest intercept(HttpRequest request, InterceptorChain chain) throws IOException {
        log.info("→ {} {} headers={}",
                request.method(),
                request.uri(),
                HeaderSanitizer.sanitize(request.headers()));
        return chain.proceed(request);
    }
}
```

Register on the builder:

```java
NativeRestClient client = NativeRestClient.builder("https://api.example.com")
        .addInterceptor(new LoggingInterceptor())
        .build();
```

### Basic Auth

```java
NativeRestClient client = NativeRestClient.builder("https://api.example.com")
        .addInterceptor(new BasicAuthInterceptor("alice", "s3cr3t"))
        .build();
```

Static credentials, or supply a `Supplier<String>` for dynamic rotation:

```java
.addInterceptor(new BasicAuthInterceptor(
        () -> config.getUsername(),
        () -> secretStore.getPassword()
))
```

### Bearer Token

```java
NativeRestClient client = NativeRestClient.builder("https://api.example.com")
        .addInterceptor(new BearerAuthInterceptor(() -> tokenStore.currentToken()))
        .build();
```

If the supplier returns `null` or an empty string, the `Authorization` header is
omitted rather than sending an invalid value.

### OAuth 2.0 (Refresh Tokens)

Use the provided OAuth helper types to automatically refresh access tokens before
expiry and inject them on each request. Always load secrets from your platform's
secret store — never hard-code them.

```java
TokenFetcher fetcher = (clientId, clientSecret, refreshToken) ->
        oauthServer.refreshToken(clientId, clientSecret, refreshToken);

RefreshingTokenManager tokenManager = new RefreshingTokenManager(
        fetcher,
        System.getenv("OAUTH_CLIENT_ID"),
        System.getenv("OAUTH_CLIENT_SECRET"),
        secureStore.get("refresh-token"),
        scheduledExecutorService
);

NativeRestClient client = NativeRestClient.builder("https://api.example.com")
        .addInterceptor(new OAuthInterceptor(tokenManager::currentAccessToken))
        .build();
```

---

## Stage 2 — Exchange Interceptors (`HttpExchangeInterceptor`)

`HttpExchangeInterceptor` wraps the entire `HttpClient.send(..)` call, giving
access to both the `HttpRequest` **and** the `HttpResponse`. This is the right
place for:

- Collecting latency metrics
- Retry logic (including response-status-aware retries)
- Token refresh on 401
- Response debugging / logging

```java
public interface HttpExchangeInterceptor {
    <T> HttpResponse<T> intercept(HttpRequest request, ExchangeChain<T> chain)
            throws IOException, InterruptedException;

    interface ExchangeChain<T> {
        HttpResponse<T> proceed(HttpRequest request) throws IOException, InterruptedException;
    }
}
```

Multiple exchange interceptors execute in **registration order** (first registered
= outermost wrapper):

```java
NativeRestClient client = NativeRestClient.builder("https://api.example.com")
        .addExchangeInterceptor(new MetricsExchangeInterceptor(recorder))    // outermost
        .addExchangeInterceptor(new RetryOnServerErrorInterceptor(3, 200L))
        .addExchangeInterceptor(new ResponseLoggingExchangeInterceptor())    // innermost
        .build();
```

### MetricsExchangeInterceptor

Records HTTP call latency, status code, and I/O errors via a `MetricsRecorder`.
The built-in `MicrometerMetricsRecorder` integrates with any Micrometer backend
(Prometheus, Datadog, OTLP, etc.).

```java
// In Spring Boot, inject the auto-configured MeterRegistry instead of SimpleMeterRegistry.
var registry = new SimpleMeterRegistry();
var recorder = new MicrometerMetricsRecorder(registry);

NativeRestClient client = NativeRestClient.builder("https://api.example.com")
        .addExchangeInterceptor(new MetricsExchangeInterceptor(recorder))
        .build();
```

**Micrometer Timer name:** `http_client_requests`

**Tags emitted:**

| Tag | Example values |
|---|---|
| `method` | `GET`, `POST` |
| `uri` | `/users/{id}` (path-normalised — numeric segments replaced) |
| `status` | `200`, `404`, `IO_ERROR` |
| `outcome` | `SUCCESS`, `CLIENT_ERROR`, `SERVER_ERROR`, `REDIRECTION`, `IO_ERROR` |
| `error` | `true` / `false` |

For Prometheus scraping, add `micrometer-registry-prometheus` to your classpath and
configure Spring Boot Actuator — metrics are then available at `/actuator/prometheus`.

To plug in a completely different backend, implement `MetricsRecorder` directly:

```java
public final class DatadogMetricsRecorder implements MetricsRecorder {
    @Override
    public void recordHttpCall(String method, URI uri, int status,
                               long durationNanos, boolean error) {
        // forward to your Datadog/Statsd client
    }
}
```

### RetryOnServerErrorInterceptor

Retries idempotent methods (`GET`, `HEAD`) on 5xx responses or I/O exceptions,
with exponential backoff capped at 30 s. Non-idempotent methods (`POST`, `PUT`,
`PATCH`, `DELETE`) are never retried.

```java
NativeRestClient client = NativeRestClient.builder("https://api.example.com")
        .addExchangeInterceptor(new RetryOnServerErrorInterceptor(
                3,      // maxAttempts (includes the first attempt)
                200L    // initialBackoffMillis — doubles each retry, max 30 000 ms
        ))
        .build();
```

### TokenRefreshExchangeInterceptor

On a `401 Unauthorized` response, invokes the refresh `Runnable`, then retries
the request once with an updated `Authorization: Bearer <newToken>` header. If the
retry also returns 401, that response is returned as-is — no infinite loop.

```java
AtomicReference<String> token = new AtomicReference<>(fetchInitialToken());

NativeRestClient client = NativeRestClient.builder("https://api.example.com")
        .addInterceptor(new BearerAuthInterceptor(token::get))
        .addExchangeInterceptor(new TokenRefreshExchangeInterceptor(
                token::get,
                () -> token.set(oauthClient.refreshAccessToken())
        ))
        .build();
```

### ResponseLoggingExchangeInterceptor

Logs response status code, URI, and headers at `INFO` level. Logs a truncated
body preview (first 1 024 bytes) at `DEBUG` level for `String` responses.

```java
NativeRestClient client = NativeRestClient.builder("https://api.example.com")
        .addExchangeInterceptor(new ResponseLoggingExchangeInterceptor())
        .build();
```

### Custom HttpExchangeInterceptor

```java
public final class CircuitBreakerInterceptor implements HttpExchangeInterceptor {

    @Override
    public <T> HttpResponse<T> intercept(HttpRequest request, ExchangeChain<T> chain)
            throws IOException, InterruptedException {
        if (isCircuitOpen()) {
            throw new IOException("Circuit open — skipping call to " + request.uri());
        }
        try {
            HttpResponse<T> response = chain.proceed(request);
            recordSuccess();
            return response;
        } catch (IOException e) {
            recordFailure();
            throw e;
        }
    }
}
```

---

## Pluggable Converters

By default, the client uses:

1. `StringConverter` — handles `String` return types.
2. `JacksonConverter` — handles everything else (POJOs, `List<T>`, `Map<K,V>`, etc.).

Converters are consulted in **registration order**; if none matches, the client
falls back to Jackson. Add custom converters before calling `build()`:

```java
public final class XmlResponseConverter implements ResponseConverter {

    @Override
    public boolean canConvert(JavaType type) {
        return type.getRawClass().isAnnotationPresent(XmlRootElement.class);
    }

    @Override
    public Object convert(InputStream body, JavaType type) throws IOException {
        JAXBContext ctx = JAXBContext.newInstance(type.getRawClass());
        return ctx.createUnmarshaller().unmarshal(body);
    }
}
```

```java
NativeRestClient client = NativeRestClient.builder("https://api.example.com")
        .addConverter(new XmlResponseConverter())   // consulted before Jackson
        .build();
```

---

## Response Envelopes

By default, non-2xx responses throw `ApiException`. Declare methods returning
`HttpResponseEnvelope<T>` to access status code, headers, and body without
exceptions — even for 4xx and 5xx responses.

```java
public interface UserService {

    // Default: throws ApiException on non-2xx
    @GET("/users/{id}")
    User getUser(@Path("id") long id);

    // Envelope: never throws ApiException — caller inspects the status
    @GET("/users/{id}")
    HttpResponseEnvelope<User> getUserEnvelope(@Path("id") long id);
}
```

Usage:

```java
HttpResponseEnvelope<User> response = api.getUserEnvelope(42L);

if (response.isSuccessful()) {
    User user = response.body();
    String traceId = response.headers().firstValue("X-Trace-Id").orElse("none");
} else {
    System.err.printf("Failed: %d%n", response.status());
}
```

`HttpResponseEnvelope<T>` also works with `CompletableFuture`:

```java
@GET("/users/{id}")
CompletableFuture<HttpResponseEnvelope<User>> getUserAsync(@Path("id") long id);
```

---

## Form URL Encoded Requests

Use `@FormUrlEncoded` on the method and `@Field` on each parameter:

```java
public interface AuthApi {

    @FormUrlEncoded
    @POST("/login")
    TokenResponse login(
            @Field("username") String username,
            @Field("password") String password
    );
}
```

The request body will be encoded as `application/x-www-form-urlencoded`.

---

## Error Handling

| Exception | When thrown |
|---|---|
| `ApiException` | Non-2xx response for methods that do **not** return `HttpResponseEnvelope<T>` |
| `RestClientException` | Framework misconfiguration, serialisation failure, or interceptor error |

```java
try {
    User user = api.getUser(999L);
} catch (ApiException e) {
    int status = e.getStatusCode();       // e.g. 404
    String body = e.getResponseBody();    // raw response body string
    boolean is4xx = e.isClientError();
    boolean is5xx = e.isServerError();
}
```

---

## Security Recommendations

- **`HeaderSanitizer`** — the built-in `LoggingInterceptor` passes request headers through
  `HeaderSanitizer.sanitize(..)` before logging. By default it redacts `Authorization`,
  `X-Api-Key`, and `Cookie` headers. Add your own sensitive header names as needed.

- **Credentials via `Supplier<String>`** — `BasicAuthInterceptor` and `BearerAuthInterceptor`
  both accept `Supplier<String>` so credentials are fetched from a secure store at call
  time, not stored as final strings.

- **OAuth secrets** — always load `clientId`, `clientSecret`, and `refreshToken` from
  environment variables or a secrets manager. Never hard-code them.

- **Per-request timeout** — the builder sets a 10-second connect timeout by default.
  For read/response timeouts, use `HttpRequest.Builder.timeout(Duration)` in a custom
  `ClientInterceptor`, or call `.orTimeout(n, unit)` on the returned `CompletableFuture`.

---

## Production-Ready Client Example

```java
var meterRegistry   = new SimpleMeterRegistry();   // or inject Spring's MeterRegistry
var metricsRecorder = new MicrometerMetricsRecorder(meterRegistry);

AtomicReference<String> token = new AtomicReference<>(fetchInitialToken());

NativeRestClient client = NativeRestClient.builder("https://api.example.com")

        // Virtual threads for high-concurrency workloads
        .executor(Executors.newVirtualThreadPerTaskExecutor())

        // Custom ObjectMapper (optional)
        .objectMapper(new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))

        // Stage 1 — request interceptors (run before any network I/O)
        .addInterceptor(new LoggingInterceptor())
        .addInterceptor(new BearerAuthInterceptor(token::get))

        // Stage 2 — exchange interceptors (registration order = execution order)
        .addExchangeInterceptor(new MetricsExchangeInterceptor(metricsRecorder))  // outermost
        .addExchangeInterceptor(new RetryOnServerErrorInterceptor(3, 200L))
        .addExchangeInterceptor(new ResponseLoggingExchangeInterceptor())
        .addExchangeInterceptor(new TokenRefreshExchangeInterceptor(
                token::get,
                () -> token.set(oauthClient.refreshAccessToken())))               // innermost

        .build();

UserService api = client.create(UserService.class);
```

---

## Builder Reference

| Method | Description |
|---|---|
| `builder(String baseUrl)` | Creates a new builder; trailing `/` is stripped automatically |
| `.httpClient(HttpClient)` | Provide a pre-configured `HttpClient` (overrides `.executor`) |
| `.executor(Executor)` | Sets the executor used by the internally created `HttpClient` |
| `.objectMapper(ObjectMapper)` | Custom `ObjectMapper`; a default one with `JavaTimeModule` is used if omitted |
| `.addInterceptor(ClientInterceptor)` | Appends a Stage 1 request interceptor |
| `.addExchangeInterceptor(HttpExchangeInterceptor)` | Appends a Stage 2 exchange interceptor |
| `.addConverter(ResponseConverter)` | Prepends a custom converter before Jackson |
| `.build()` | Constructs and returns the `NativeRestClient` |

---

## Running the Example

```bash
mvn compile exec:java \
  -Dexec.mainClass=io.declarative.http.example.Main
```

Requires network access. The example targets `https://jsonplaceholder.typicode.com`.

---

## Running the Tests

```bash
mvn test
```

Tests use [WireMock](https://wiremock.org/) for HTTP stubbing — no external
network required.

---

## License

Apache 2.0 — see [LICENSE](LICENSE).
