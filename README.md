# Native REST Client for Java

> Copyright (C) 2024–2026  Debopam Poddar
> 
> This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, version 3.
>
> This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

A lightweight, near-zero-dependency declarative HTTP client for Java 21+.

Designed as a modern, native alternative to Retrofit and Feign, this library allows you to
translate REST APIs into Java interfaces using annotations. By stripping away third-party
networking engines like OkHttp, it relies entirely on the JDK's built-in
[`java.net.http.HttpClient`](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html)
and is fully compatible with virtual threads.

Whether you are building microservices or desktop applications, this library provides a clean,
familiar developer experience fully optimised for modern Java.

It is a good fit when you want typed, annotation-driven HTTP calls without adopting
a transport stack such as OkHttp or a large application framework. The JDK handles
transport; this library handles the service interface, request construction,
serialization, response conversion, and opt-in policies.

Examples use normal imports from `io.declarative.http.*` and standard JDK types;
imports are omitted where they would obscure the request/client relationship.

## Start here

The shortest path from interface to HTTP call is:

1. Add the Maven dependency and define an annotated interface.
2. Build one reusable `NativeRestClient` for the target API.
3. Create a typed proxy and call it synchronously or through `CompletableFuture`.
4. Add only the policies you need: authentication, retry, metrics, or logging.
5. Close a client that owns its `HttpClient` when its application component stops.

If you are new to the library, read **Quick Start**, then choose the return style in
**Response Envelopes** and add **OAuth 2.0** only when the API requires it.

### Choose your path

| If you need to… | Start here |
|---|---|
| Call JSON endpoints from a typed interface | [Quick Start](#quick-start) |
| Add request IDs, idempotency keys, or a one-off timeout | [Request options](#request-options) |
| Inspect a 404/422 response instead of throwing | [Response envelopes](#response-envelopes) |
| Turn API errors into domain exceptions | [Error handling](#error-handling) |
| Send login forms or upload files | [Form URL encoded requests](#form-url-encoded-requests) and [Multipart requests](#multipart-requests) |
| Authenticate service-to-service calls | [OAuth 2.0 client credentials](#oauth-20-client-credentials) |
| Retry safely after temporary failures | [RetryOnServerErrorInterceptor](#retryonservererrorinterceptor) |

---

## ✨ Key Features

- **Zero Networking Dependencies**
  Uses the JDK's `java.net.http.HttpClient` — no OkHttp or Netty required.

- **Declarative API Interfaces**
  Define your HTTP API as annotated Java interfaces:
  `@GET`, `@POST`, `@PUT`, `@DELETE`, `@PATCH`, `@Path`, `@Query`, `@QueryMap`,
  `@Body`, `@Url`, `@Header`, `@HeaderMap`, `@Headers`, `@Field`, `@FormUrlEncoded`,
  `@Part`, and `@Multipart`.

- **Sync & Async Execution**
  - Synchronous methods returning plain types (`User`, `List<User>`, `String`, `InputStream`, `void`).
  - Asynchronous methods returning `CompletableFuture<T>`.

- **Two-Stage Interceptor Pipeline**
  - **Stage 1 — `ClientInterceptor`**: request-only transformations (auth headers, static headers).
  - **Stage 2 — exchange interceptors**: sync and async around-call wrappers (metrics, retry, token refresh, response logging).

- **Pluggable Message Converters**
  Built-in: `StringConverter` (raw text) and `JacksonConverter` (JSON).
  Extendable via `NativeRestClient.Builder#addConverter` (XML, Protobuf, CSV, etc.).

- **Response Envelope (Optional)**
  Use `HttpResponseEnvelope<T>` to access HTTP status and headers on both successful
  and error responses without throwing `ApiException`.

- **Form URL Encoding**
  `@FormUrlEncoded` + `@Field` for `application/x-www-form-urlencoded` bodies.

- **Multipart Uploads**
  `@Multipart` + `@Part` for text and in-memory binary form-data uploads.

- **Auth Helpers**
  `BasicAuthInterceptor`, `BearerAuthInterceptor`, and full OAuth 2.0 token-refresh
  infrastructure (`TokenFetcher`, `AccessToken`, `RefreshingTokenManager`, `OAuth2Decorator`).

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
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Use the version published by your repository; the source tree currently builds as
`1.0.0-SNAPSHOT`.

Requires Java 21+.

The base library has no networking engine dependency. Jackson and SLF4J provide JSON
conversion and logging APIs; Micrometer is optional and needed only when you use the
Micrometer recorder.

---

## Quick Start

### 1. Define your API interface

```java
record User(long id, String name, String email) {}

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

    @POST("/users")
    User createUser(@Body User user);
}
```

Each remotely invoked method must have exactly one HTTP-method annotation. The
client validates the complete interface when `create(...)` is called, so invalid
paths, parameter bindings, or return types fail before the first network request.

### 2. Build a client

```java
NativeRestClient client = NativeRestClient.builder("https://api.example.com")
        .requestTimeout(Duration.ofSeconds(30))
        .build();

UserService api = client.create(UserService.class);

// Synchronous
User user = api.getUser(42L);

// The same transport timeout and built-in policies apply asynchronously.
User asyncUser = api.getUserAsync(42L).join();
```

### 3. Send a request

```java
try (client) {
    User created = api.createUser(new User(0, "Ada", "ada@example.com"));
    List<User> page = api.listUsers(0, 20, "name");
}
```

### 4. Using virtual threads (recommended for high concurrency)

```java
NativeRestClient client = NativeRestClient.builder("https://api.example.com")
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .build();
```

### 5. Pick the return style that fits the call

| Need | Service-method return type | Responsibility |
|---|---|---|
| A decoded response body | `User` or `List<User>` | The client closes the response body. |
| Non-blocking execution | `CompletableFuture<User>` | Handle failures with the returned future. |
| Status and headers | `HttpResponseEnvelope<User>` | Check `isSuccessful()` and inspect `errorBody()` on errors. |
| Streaming download | `InputStream` or `HttpResponseEnvelope<InputStream>` | The caller must close the stream. |

For a streaming method, ownership is explicit:

```java
try (InputStream body = filesApi.download(reportId)) {
    Files.copy(body, Path.of("report.pdf"));
}
```

### 6. Know the safe defaults

- Requests time out after 30 seconds by default; the internally-created JDK client has a
  10-second connection timeout.
- The default JSON mapper understands Java time types and ignores unknown response fields.
- `Authorization`, cookies, and common API-key headers are redacted from built-in logs.
- Retry is limited to `GET` and `HEAD`; it closes discarded responses before retrying.
- Invalid service declarations fail from `client.create(...)`, before any network request.

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
| `@Multipart` | method | Encodes `@Part` params as `multipart/form-data` |
| `@Path("name")` | parameter | URL path segment replacement (`{name}`) — null values throw `IllegalArgumentException` |
| `@Query("name")` | parameter | Appends `?name=value` to the URL; null values are omitted |
| `@QueryMap` | parameter | Appends all `Map<String, ?>` entries as query parameters |
| `@Header("name")` | parameter | Sets a single request header per call |
| `@HeaderMap` | parameter | Merges all `Map<String, String>` entries as request headers |
| `@Body` | parameter | Serialises the parameter as the request body (JSON by default) |
| `@Url` | parameter | Overrides the full request URL (ignores the path in `@GET` etc.) |
| `@Field("name")` | parameter | One form field; requires `@FormUrlEncoded` on the method |
| `@Part("name")` | parameter | One text or `MultipartPart` value; requires `@Multipart` on the method |

### A declarative request in one place

```java
public interface OrdersApi {

    @Headers({"Accept: application/json"})
    @PATCH("/orders/{orderId}")
    Order update(
            @Path("orderId") String orderId,
            @Query("dryRun") boolean dryRun,
            @Header("If-Match") String version,
            @Body UpdateOrderRequest request,
            RequestOptions options);
}
```

The annotations describe only this request. Shared behavior—authentication,
logging, metrics, retries, and token refresh—belongs on the reusable client
builder through interceptors or decorators.

### Declaration rules

- A remotely-invoked method has exactly one of `@GET`, `@POST`, `@PUT`, `@PATCH`, or `@DELETE`.
- Each ordinary parameter has one binding annotation. `RequestOptions` is the sole
  unannotated binding and must be the final parameter.
- `@FormUrlEncoded` requires one or more `@Field` parameters and cannot be combined with `@Body`.
- `@Multipart` requires one or more `@Part` parameters and cannot be combined with `@Body`, `@Field`, or `@FormUrlEncoded`.
- Return `T`, `CompletableFuture<T>`, `HttpResponseEnvelope<T>`, or
  `CompletableFuture<HttpResponseEnvelope<T>>`; use `InputStream` only when the caller will close it.

## Request Options

`RequestOptions` is not an annotation. Declare it as the final, unannotated
parameter when one call needs extra headers or a timeout override.

```java
@POST("/payments")
Payment createPayment(@Body PaymentRequest request, RequestOptions options);
```

```java
RequestOptions options = RequestOptions.builder()
        .header("X-Correlation-Id", correlationId)
        .header("Idempotency-Key", idempotencyKey)
        .timeout(Duration.ofSeconds(5))
        .build();
```

Options override static and parameter headers for that call. Client interceptors,
such as authentication, still execute afterwards and may apply their own final policy.

Use it for data that varies per operation—such as trace IDs, idempotency keys, or
shorter deadlines—not for shared credentials. Authentication belongs in a client
interceptor so it is applied consistently to every request.

---

## Architecture & Execution Flow

For the complete request lifecycle and the reasoning behind the OAuth2 design, see
[the design and gap analysis](docs/rest-client-gap-analysis.md).

---

## Interceptor Pipeline

When a proxy method is invoked, requests flow through two sequential stages before reaching
`HttpClient.send(..)` or `sendAsync(..)`:

```
Method call
│
▼
[Stage 1 — ClientInterceptor chain]
· modifies the outgoing HttpRequest only
· examples: LoggingInterceptor, BasicAuthInterceptor, BearerAuthInterceptor
│
▼
[Stage 2 — sync or async exchange-interceptor chain]
· wraps HttpClient.send(..) — sees both HttpRequest and HttpResponse
· examples: MetricsExchangeInterceptor, RetryOnServerErrorInterceptor,
TokenRefreshExchangeInterceptor, ResponseLoggingExchangeInterceptor
│
▼
HttpClient.send(request) / sendAsync(request)
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
                HeaderSanitizer.sanitize(request.uri()),
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

### OAuth 2.0 Managed Tokens

Use the provided OAuth helper types to automatically refresh access tokens before
expiry and inject them on each request. Always load secrets from your platform's
secret store — never hard-code them.

```java
TokenFetcher fetcher = () -> {
    TokenResponse response = oauthServer.fetchToken();
    return new AccessToken(
            response.accessToken(),
            Instant.now().plusSeconds(response.expiresIn()));
};

RefreshingTokenManager tokenManager = new RefreshingTokenManager(
        fetcher,
        Duration.ofSeconds(30),
        scheduledExecutorService
);

NativeRestClient client = OAuth2Decorator.with(tokenManager)
        .applyTo(NativeRestClient.builder("https://api.example.com"))
        .build();
```

The decorator uses the same manager for proactive attachment and reactive 401
handling. It refreshes only a Bearer-challenged 401 response, retries at most once,
and never treats 403 as a token-refresh signal. Close both the client and token
manager when the application component stops. A caller-supplied scheduler remains
caller-owned; passing `null` creates an internal daemon scheduler.

### OAuth 2.0 Client Credentials

For the common machine-to-machine grant, use the JDK-only
`ClientCredentialsTokenFetcher`. It sends the client secret in HTTP Basic
authentication, requires an `access_token` and positive `expires_in`, and does
not log token endpoint response bodies.

```java
TokenFetcher fetcher = ClientCredentialsTokenFetcher.builder(
        URI.create("https://identity.example.com/oauth/token"),
        secretStore.clientId(),
        secretStore.clientSecret())
        .scope("payments.read")
        .audience("https://payments.example.com") // only for servers that use it
        .requestTimeout(Duration.ofSeconds(10))
        .build();

try (RefreshingTokenManager tokens = new RefreshingTokenManager(fetcher, null);
     NativeRestClient client = OAuth2Decorator.with(tokens)
             .applyTo(NativeRestClient.builder("https://payments.example.com"))
             .build()) {
    PaymentsApi api = client.create(PaymentsApi.class);
}
```

---

## Stage 2 — Exchange Interceptors (`HttpExchangeInterceptor`)

`HttpExchangeInterceptor` wraps the entire synchronous `HttpClient.send(..)` call, giving
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

For asynchronous methods, implement `AsyncHttpExchangeInterceptor`. The built-in
metrics, retry, response-logging, and OAuth2 interceptors implement both interfaces;
registering them with `addExchangeInterceptor` enables both paths automatically.

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

Micrometer is an optional dependency. Consumers using this adapter must declare
`micrometer-core` and their chosen registry; neither is forced on other consumers.

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

Retries idempotent methods (`GET`, `HEAD`) on 429/5xx responses or I/O exceptions.
Discarded response bodies are closed before retrying. Non-idempotent methods
(`POST`, `PUT`, `PATCH`, `DELETE`) are never retried. `Retry-After` is honored
up to the configured maximum; locally calculated delays can use jitter.

```java
NativeRestClient client = NativeRestClient.builder("https://api.example.com")
        .addExchangeInterceptor(new RetryOnServerErrorInterceptor(
                RetryPolicy.builder()
                        .maxAttempts(3)
                        .initialBackoff(Duration.ofMillis(200))
                        .maxBackoff(Duration.ofSeconds(30))
                        .jitterFactor(0.2)
                        .build()))
        .build();
```

### TokenRefreshExchangeInterceptor

On a `401 Unauthorized` response carrying a Bearer challenge, refreshes through
the shared `TokenManager` and retries once with a distinct token. It does not
refresh for 403, non-Bearer 401 responses, blank tokens, or unchanged tokens.

```java
NativeRestClient client = OAuth2Decorator.with(tokenManager)
        .applyTo(NativeRestClient.builder("https://api.example.com"))
        .build();
```

### ResponseLoggingExchangeInterceptor

Logs response status code, query-free URI, and sanitized headers at `INFO` level.
Response bodies are never logged.

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

By default, 4xx and 5xx responses use `ApiException`. You can replace that
mapping with an `ErrorDecoder`. Declare methods returning `HttpResponseEnvelope<T>`
to access status code, headers, and body without invoking either error mapping—even
for 4xx and 5xx responses.

```java
public interface UserService {

    // Default: throws ApiException on 4xx/5xx (or your ErrorDecoder result)
    @GET("/users/{id}")
    User getUser(@Path("id") long id);

    // Envelope: caller inspects the status; ErrorDecoder is not invoked
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
    System.err.printf("Failed: %d, body=%s%n",
            response.status(), response.errorBody());
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

## Multipart Requests

Use `@Multipart` with `@Part` values. Scalar values are sent as UTF-8 text;
use `MultipartPart` for bytes, a filename, or an explicit part content type.

```java
public interface FilesApi {

    @Multipart
    @POST("/files")
    FileMetadata upload(
            @Part("description") String description,
            @Part("file") MultipartPart file);
}

MultipartPart file = MultipartPart.fromFile(
        Path.of("report.pdf"), "application/pdf");
```

Multipart payloads are intentionally in-memory. For very large files, use a
dedicated streaming endpoint until streaming multipart publishers are added.

---

## Error Handling

| Exception | When thrown |
|---|---|
| `ApiException` | Default mapping for 4xx/5xx responses from methods that do **not** return `HttpResponseEnvelope<T>` |
| Custom exception | A 4xx/5xx response when configured through `.errorDecoder(...)` |
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

For domain-specific exceptions, configure an `ErrorDecoder`. It receives the
status, response headers, and a bounded UTF-8 error body. It is used for both
synchronous and asynchronous service methods; envelope methods continue to
return `HttpResponseEnvelope<T>` instead.

```java
NativeRestClient client = NativeRestClient.builder("https://api.example.com")
        .errorDecoder((status, headers, body) ->
                new RemoteApiException(status,
                        headers.firstValue("X-Error-Code").orElse("unknown"), body))
        .build();
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

- **Per-request timeout** — the internally-created HTTP client has a 10-second connect
  timeout, and each request has a 30-second timeout by default. Override it with
  `.requestTimeout(Duration)`; it applies to sync and async calls.

---

## Production-Ready Client Example

```java
var meterRegistry   = new SimpleMeterRegistry();   // or inject Spring's MeterRegistry
var metricsRecorder = new MicrometerMetricsRecorder(meterRegistry);
var tokenManager = new RefreshingTokenManager(
        tokenFetcher, Duration.ofSeconds(30), scheduledExecutorService);

NativeRestClient.Builder builder = NativeRestClient.builder("https://api.example.com")

        // Virtual threads for high-concurrency workloads
        .executor(Executors.newVirtualThreadPerTaskExecutor())

        // Custom ObjectMapper (optional)
        .objectMapper(new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))

        // Stage 1 — request interceptors (run before any network I/O)
        .addInterceptor(new LoggingInterceptor())
        // Stage 2 — exchange interceptors (registration order = execution order)
        .addExchangeInterceptor(new MetricsExchangeInterceptor(metricsRecorder))  // outermost
        .addExchangeInterceptor(new RetryOnServerErrorInterceptor(
                RetryPolicy.builder().maxAttempts(3).build()))
        .addExchangeInterceptor(new ResponseLoggingExchangeInterceptor())
        .requestTimeout(Duration.ofSeconds(30));

NativeRestClient client = OAuth2Decorator.with(tokenManager)
        .applyTo(builder)
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
| `.requestTimeout(Duration)` | Sets the per-request timeout; defaults to 30 seconds |
| `.errorDecoder(ErrorDecoder)` | Maps non-envelope 4xx/5xx responses to application exceptions |
| `.addInterceptor(ClientInterceptor)` | Appends a Stage 1 request interceptor |
| `.addExchangeInterceptor(HttpExchangeInterceptor)` | Appends a sync Stage 2 interceptor and its async side when implemented |
| `.addAsyncExchangeInterceptor(AsyncHttpExchangeInterceptor)` | Appends an async-only Stage 2 interceptor |
| `.addConverter(ResponseConverter)` | Prepends a custom converter before Jackson |
| `.build()` | Constructs and returns the `NativeRestClient` |

`NativeRestClient` is `AutoCloseable`. It closes an internally-created `HttpClient`
but never closes a client supplied through `.httpClient(...)`.

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

native-rest-client-java  
Copyright (c) 2024–2026 Debopam Poddar

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License version 3
as published by the Free Software Foundation.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
[LICENSE](LICENSE) file for more details.
