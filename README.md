# native-rest-client-java

A lightweight, zero-dependency declarative HTTP client built exclusively for Java 21+.

Designed as a modern, native alternative to [Retrofit](https://square.github.io/retrofit/), this library allows you to translate REST APIs into Java interfaces using annotations. By stripping away heavy third-party networking engines like OkHttp, it relies entirely on the JDK's built-in `java.net.http.HttpClient`.

Whether you are building microservices or desktop applications, this library provides a clean, Retrofit-like developer experience fully optimized for Project Loom and modern Java.

### ✨ Key Features
* **Zero Dependencies:** No OkHttp, no Retrofit. Just pure Java 21 (with optional Jackson for JSON serialization).
* **Declarative Routing:** Use standard annotations (`@GET`, `@POST`, `@Path`, `@Query`, `@Body`) to define your API contracts.
* **Sync & Async by Default:** Return standard POJOs for synchronous blocking calls, or `CompletableFuture<T>` for fully non-blocking, Virtual Thread-friendly execution.
* **Interceptor Chains:** Easily inject custom middleware for logging, Basic Auth, or complex OAuth 2.0 token refreshing.
* **Multipart File Uploads:** Native support for `multipart/form-data` using `@Multipart` and `@Part` annotations.
* **Dynamic Environments:** Switch base URLs at runtime without rebuilding the client.