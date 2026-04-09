# Native REST Client for Java

A lightweight, minimal-dependency declarative HTTP client built exclusively for Java 21+.

Designed as a modern, native alternative to Retrofit and Feign, this library allows you to translate REST APIs into Java interfaces using annotations. By stripping away heavy third-party networking engines like OkHttp, it relies entirely on the JDK's built-in `java.net.http.HttpClient` and modern Virtual Threads (Project Loom).

Whether you are building microservices or desktop applications, this library provides a clean, familiar developer experience fully optimized for modern Java.

---

## ✨ Key Features

* **Zero Networking Dependencies:** Relies entirely on the native Java 21 HTTP Client.
* **Pluggable Message Converters:** Modular serialization/deserialization. (Comes with a Jackson adapter out of the box).
* **Declarative Routing:** Define your API contracts simply using annotations (`@GET`, `@POST`, `@PUT`, `@DELETE`, `@PATCH`, `@Path`, `@Query`, `@QueryMap`, `@Body`, `@Url`).
* **Advanced Headers:** Supports dynamic `@Header`, `@HeaderMap`, and static `@Headers`.
* **OOM-Safe Binary Streaming:** Native `InputStream` support. Download massive files without buffering payloads into memory, completely preventing `OutOfMemoryError`s.
* **Sync & Async Operations:** Support for synchronous blocking calls, as well as asynchronous execution returning `CompletableFuture<T>`.
* **Form URL Encoding:** Supports `@FormUrlEncoded` requests.
* **Interceptor Chains:** Extensible request pipeline. Includes built-in support for Logging, Retries, Basic Auth, Bearer Auth, and OAuth 2.0 automatic token refreshing.

---

## 🚀 Getting Started

### 1. Define your API Contract

Use annotations to define the HTTP methods, paths, headers, and request bodies.

```java
import io.declarative.http.api.annotation.*;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface UserService {
    // 1. Asynchronous GET
    @GET("/api/users/{id}")
    CompletableFuture<User> getUserByIdAsync(@Path("id") int id);

    // 2. Synchronous GET with Query params
    @GET("/api/users")
    List<User> searchUsers(@Query("name") String name, @Query("role") String role);

    // 3. GET with dynamic Query Map
    @GET("/api/users/search")
    List<User> searchUsers(@QueryMap Map<String, Object> filters);

    // 4. POST with a JSON Body and Dynamic Headers
    @Headers("Accept: application/json")
    @POST("/api/users")
    User createUser(@Header("Authorization") String token, @Body User newUser);

    // 5. GET with Dynamic Headers Map
    @GET("/api/users/{id}")
    User getUserWithHeaders(@Path("id") Long id, @HeaderMap Map<String, String> traceHeaders);

    // 6. Form URL Encoded payload (e.g., OAuth login)
    @FormUrlEncoded
    @POST("/api/login")
    TokenResponse login(@Field("username") String user, @Field("password") String pass);
    
    // 7. Large File Download (OOM-Safe Streaming)
    @GET
    InputStream downloadLargeReport(@Url String directCdnUrl);
}
```

### 2. Build the Client and Add Interceptors

Create your client using the `NativeRestClient.builder()`. You can easily plug in logging, authentication, and retry mechanisms.

```java
import io.declarative.http.api.auth.BearerAuthInterceptor;
import io.declarative.http.api.interceptors.LoggingInterceptor;
import io.declarative.http.api.interceptors.RetryInterceptor;
import io.declarative.http.client.NativeRestClient;

import java.time.Duration;

public class Main {
    public static void main(String[] args) throws Exception {
        NativeRestClient client = NativeRestClient
                .builder("https://api.example.com")
                .connectTimeout(Duration.ofSeconds(5))
                .addInterceptor(new LoggingInterceptor())
                .addInterceptor(new BearerAuthInterceptor(() -> "your-access-token"))
                .addInterceptor(new RetryInterceptor(3, 500L))
                .build();

        UserService api = client.create(UserService.class);
        
        // Make requests
        User user = api.getUser(42L);
    }
}
```

### 3. Advanced: OAuth 2.0 Automatic Token Refresh

The library includes robust support for OAuth 2.0, allowing you to define a `TokenFetcher` that automatically refreshes your access tokens in the background before they expire.

```java
import io.declarative.http.api.auth.oauth.*;
import io.declarative.http.client.NativeRestClient;
import java.time.Duration;

// 1. Define how to fetch a new token
TokenFetcher fetcher = () -> {
    // Implement your token refresh logic here
    // e.g., make a request to the /oauth/token endpoint
    return new AccessToken("new-access-token", Instant.now().plusSeconds(3600));
};

// 2. Create a TokenManager that proactively refreshes the token
// Refresh 60 seconds before expiry, checking every 30 seconds
TokenManager tokenManager = new RefreshingTokenManager(
        fetcher,
        Duration.ofSeconds(60),
        null, // Optional initial token
        Duration.ofSeconds(30)
);

// 3. Add the OAuthInterceptor to your client
NativeRestClient client = NativeRestClient
        .builder("https://api.example.com")
        .addInterceptor(new OAuthInterceptor(tokenManager))
        .build();
```
