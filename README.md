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
* **Sync & Async Operations:** Support for synchronous blocking calls, as well as asynchronous execution returning `CompletableFuture<T>`.
* **Form URL Encoding:** Supports `@FormUrlEncoded` requests.
* **Interceptor Chains:** Extensible request pipeline. Includes built-in support for Logging, Retries, Basic Auth, Bearer Auth, and OAuth 2.0 automatic token refreshing.

---

# native-rest-client-java

![Build](https://github.com/debopampoddar/native-rest-client-java/actions/workflows/ci.yml/badge.svg)
![Java](https://img.shields.io/badge/Java-21%2B-blue)
![License](https://img.shields.io/badge/License-Apache%202.0-green)

A zero-dependency Java 21 REST client built on `java.net.http.HttpClient` with
annotation-driven interface proxies, pluggable interceptors, and a converter SPI.

## Add to Your Project

```xml
<dependency>
  <groupId>io.declarative.http</groupId>
  <artifactId>native-rest-client</artifactId>
  <version>1.0.0</version>
</dependency>
```

## Quick Start

```java
// 1. Define your API interface
public interface UserService {
    @GET("/users/{id}")
    CompletableFuture<User> getUserAsync(@Path("id") int id);
}

// 2. Build a client and call the API  ← FIX: was api.getUser(42L)
NativeRestClient client = NativeRestClient.builder("https://api.example.com").build();
UserService api = client.create(UserService.class);
User user = api.getUserAsync(42).join();   // correct method name
```

## Auth Interceptors

```java
// Basic auth
.addInterceptor(new BasicAuthInterceptor("alice", "secret"))

// Bearer token (static)
.addInterceptor(new BearerAuthInterceptor("my-static-token"))

// OAuth 2.0 with auto-refresh  ← FIX: was null, // Optional initial token
ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
TokenManager tokens = new RefreshingTokenManager(
    () -> oauthClient.fetchToken(),   // TokenFetcher
    Duration.ofSeconds(30),           // refresh before expiry  ← FIX: was mislabelled
    scheduler                         // ← this is the ScheduledExecutorService
);
.addInterceptor(new OAuthInterceptor(tokens::getAccessToken))
```
