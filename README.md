# Native REST Client for Java

A lightweight, minimal-dependency declarative HTTP client built exclusively for Java 21+.

Designed as a modern, native alternative to Retrofit and Feign, this library allows you to translate REST APIs into Java interfaces using annotations. By stripping away third-party networking engines like OkHttp, it relies entirely on the JDK's built-in [`java.net.http.HttpClient`](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html) and is friendly to virtual threads.

Whether you are building microservices or desktop applications, this library provides a clean, familiar developer experience fully optimized for modern Java.

---

## ✨ Key Features

- **Zero Networking Dependencies**  
  Uses the JDK's `java.net.http.HttpClient` – no OkHttp or Netty required.

- **Declarative API Interfaces**  
  Define your HTTP API as annotated Java interfaces:
  `@GET`, `@POST`, `@PUT`, `@DELETE`, `@PATCH`, `@Path`, `@Query`, `@QueryMap`,
  `@Body`, `@Url`, `@Header`, `@HeaderMap`.

- **Sync & Async Execution**
    - Synchronous methods returning plain types (`User`, `List<User>`, `String`, `InputStream`, `void`).
    - Asynchronous methods returning `CompletableFuture<T>`.

- **Pluggable Message Converters**
    - Built-in: `StringConverter` (raw text) and `JacksonConverter` (JSON).
    - Extendable via `NativeRestClient.Builder#addConverter` (XML, Protobuf, CSV, etc.).

- **Interceptor Pipelines**
    - Request interceptors (`ClientInterceptor`) for auth headers, logging, custom headers, etc.
    - Optional around-call interceptors (`HttpExchangeInterceptor`) for metrics, retries, and circuit breakers.

- **Form URL Encoding**  
  `@FormUrlEncoded` + `@Field` for `application/x-www-form-urlencoded` bodies.

- **Response Envelope (Optional)**  
  Use `HttpResponseEnvelope<T>` to access status and headers on successful and failed calls without throwing `ApiException`.

- **Auth Helpers**
    - `BasicAuthInterceptor`
    - `BearerAuthInterceptor`
    - OAuth 2.0 helper types (token manager, interceptor) suitable for automatic token refresh.

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

// Synchronous call
User user = api.getUser(42L);

// Asynchronous call
User asyncUser = api.getUserAsync(42L)
        .orTimeout(2, TimeUnit.SECONDS)
        .join();
```

### 3. Using virtual threads (optional but recommended)

```java
Executor vthreads = Executors.newVirtualThreadPerTaskExecutor();

NativeRestClient client = NativeRestClient.builder("https://api.example.com")
        .executor(vthreads)
        .build();
```

---

## Interceptors

### Request Interceptors

Implement `ClientInterceptor` to modify outgoing requests:

```java
public final class LoggingInterceptor implements ClientInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoggingInterceptor.class);

    @Override
    public HttpRequest intercept(HttpRequest request,
                                 InterceptorChain chain) throws IOException {
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

### Auth Interceptors

#### Basic Auth

```java
NativeRestClient client = NativeRestClient.builder("https://api.example.com")
        .addInterceptor(new BasicAuthInterceptor("alice", "s3cr3t"))
        .build();
```

#### Bearer Token

```java
NativeRestClient client = NativeRestClient.builder("https://api.example.com")
        .addInterceptor(new BearerAuthInterceptor(() -> tokenStore.currentToken()))
        .build();
```

### OAuth 2.0 (Refresh Tokens)

Use the provided OAuth helper types (`TokenFetcher`, `AccessToken`, `RefreshingTokenManager`, `OAuthInterceptor`) to automatically refresh access tokens before expiry and inject them on each request. Load secrets (client ID, client secret, refresh token) from your platform's secret store, not from hard-coded strings.

---

## Pluggable Converters

By default, the client uses:

1. `StringConverter` for `String` return types.
2. `JacksonConverter` for everything else (POJOs, lists, maps, etc.).

You can add your own converters:

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

Register the converter:

```java
NativeRestClient client = NativeRestClient.builder("https://api.example.com")
        .addConverter(new XmlResponseConverter())
        .build();
```

Converters are consulted in registration order; if none matches, the client falls back to Jackson.

---

## Response Envelopes

To inspect HTTP status and headers without throwing `ApiException`, declare methods returning `HttpResponseEnvelope<T>`:

```java
public interface UserService {

    @GET("/users/{id}")
    HttpResponseEnvelope<User> getUserWithMetadata(@Path("id") long id);
}
```

Usage:

```java
HttpResponseEnvelope<User> resp = api.getUserWithMetadata(42L);

if (resp.isSuccessful()) {
    User user = resp.body();
    HttpHeaders headers = resp.headers();
} else {
    // inspect status + headers for error handling
}
```

Methods returning `T` or `CompletableFuture<T>` keep the existing behavior: non-2xx responses throw `ApiException`.

---

## Form URL Encoded Requests

Use `@FormUrlEncoded` and `@Field`:

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

---

## Error Handling

- Non-2xx responses for regular methods throw `ApiException`:
    - `getStatusCode()`, `getResponseBody()`, `isClientError()`, `isServerError()`.
- Framework misconfiguration, serialization errors, and interceptor failures throw `RestClientException`.

Typical pattern:

```java
try {
    User user = api.getUser(42L);
} catch (ApiException e) {
    if (e.isClientError()) {
        // 4xx: validation, not found, etc.
    } else if (e.isServerError()) {
        // 5xx: retry or circuit-breaker
    }
}
```

---

## Timeouts

- The builder configures a sensible default connect timeout (10 seconds).
- You can also set per-request timeouts using `HttpRequest.Builder.timeout(Duration)` via custom parameter handlers or future `@Timeout` annotations.
- For asynchronous calls, use `CompletableFuture` APIs like `orTimeout` and `completeOnTimeout`.

---

## Security Guidelines

- Never log raw `Authorization`, `Cookie`, or API key headers. `HeaderSanitizer` redacts these from logs by default.
- Do not hard-code secrets (client IDs, client secrets, tokens) in source code. Load from environment variables, KMS, Vault, or your platform’s secret manager.
- For production TLS configuration (custom trust stores, mutual TLS, certificate pinning), configure `HttpClient` via `NativeRestClient.Builder#httpClient` and pass a pre-configured instance.

---

## Testing

The project uses:

- [JUnit 5](https://junit.org/junit5/) for unit tests.
- [WireMock](http://wiremock.org/) for HTTP stubbing.

See `NativeRestClientTest` for examples of:

- Path and query parameters.
- Header maps.
- Form URL encoded bodies.
- Asynchronous calls.
- Error handling with `ApiException`.

---

## Roadmap

Planned enhancements include:

- Multipart/form-data and file upload support.
- Rich streaming and download APIs.
- Additional converter modules (XML, Protobuf).
- Optional reactive call adapters (RxJava, Reactor).

Contributions and feedback are welcome!
