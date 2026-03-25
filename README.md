# Native REST Client for Java

A lightweight, minimal-dependency declarative HTTP client built exclusively for Java 21+.

Designed as a modern, native alternative to Retrofit and Feign, this library allows you to translate REST APIs into Java interfaces using annotations. By stripping away heavy third-party networking engines like OkHttp, it relies entirely on the JDK's built-in `java.net.http.HttpClient` and modern Virtual Threads (Project Loom).

Whether you are building microservices or desktop applications, this library provides a clean, familiar developer experience fully optimized for modern Java.

---

## ✨ Key Features

* **Zero Networking Dependencies:** Relies entirely on the native Java 21 HTTP Client.
* **Pluggable Message Converters:** Modular serialization/deserialization. (Comes with a Jackson adapter out of the box).
* **Declarative Routing:** Define your API contracts simply using annotations (`@GET`, `@POST`, `@PUT`, `@DELETE`, `@Path`, `@Query`, `@Body`, `@Url`).
* **OOM-Safe Binary Streaming:** Native `InputStream` support. Download massive files without buffering payloads into memory, completely preventing `OutOfMemoryError`s.
* **Sync & Async Operations:** Support for synchronous blocking calls, as well as asynchronous execution returning `CompletableFuture<T>`.
* **Advanced Headers & Data Types:** Supports dynamic `@Header`, static `@Headers`, as well as `@FormUrlEncoded` and `@Multipart` requests.
* **Interceptor Chains:** Extensible request/response pipeline. Includes built-in support for Logging, Basic Auth, and OAuth 2.0 automatic token refreshing.

---

## 🚀 Getting Started

### 1. Define your API Contract

Use annotations to define the HTTP methods, paths, headers, and request bodies.

```java
import io.declarative.http.api.annotation.*;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;

public interface UserService {
    // 1. Asynchronous GET
    @GET("/api/users/{id}")
    CompletableFuture<User> getUserByIdAsync(@Path("id") int id);

    // 2. Synchronous GET with Query params
    @GET("/api/users")
    User searchUsers(@Query("name") String name, @Query("role") String role);

    // 3. POST with a JSON Body and Dynamic Headers
    @Headers("Accept: application/json")
    @POST("/api/users")
    User createUser(@Header("Authorization") String token, @Body User newUser);

    // 4. Form URL Encoded payload (e.g., OAuth login)
    @FormUrlEncoded
    @POST("/api/login")
    TokenResponse login(@Field("username") String user, @Field("password") String pass);
    
    // 5. Large File Download (OOM-Safe Streaming)
    @GET
    InputStream downloadLargeReport(@Url String directCdnUrl);
}
