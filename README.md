# Native REST Client for Java

A lightweight, minimal dependency declarative HTTP client built exclusively for Java 21+.

Designed as a modern, native alternative to Retrofit and Feign, this library allows you to translate REST APIs into Java interfaces using annotations. It leverages the JDK's built-in `java.net.http.HttpClient` under the hood.

Whether you are building microservices or desktop applications, this library provides a clean, familiar developer experience optimized for modern Java.

## ✨ Key Features
* **Zero Networking Dependencies:** Relies entirely on the native Java 21+ HTTP Client. (Uses Jackson strictly for JSON serialization/deserialization).
* **Declarative Routing:** Define your API contracts simply using annotations (`@GET`, `@POST`, `@PUT`, `@DELETE`, `@Path`, `@Query`, `@Body`).
* **Sync & Async Operations:** Support for synchronous blocking calls, as well as asynchronous execution returning `CompletableFuture<T>`.
* **Interceptor Chains:** Extensible request/response interceptor pipeline allowing you to inject custom behavior. Includes built-in support for Logging, Basic Auth, and OAuth 2.0 automatic token refreshing.
* **Error Handling:** Built-in extraction of error payloads natively into `ApiException`s to streamline HTTP status handling (e.g., HTTP 4xx, 5xx).

## 🚀 Getting Started

### 1. Define your API Contract

Use annotations to define the HTTP methods, paths, queries, and request bodies.

```java
import io.declarative.http.api.annotation.*;
import java.util.concurrent.CompletableFuture;

public interface UserService {
    // 1. Asynchronous GET
    @GET("/api/users/{id}")
    CompletableFuture<User> getUserByIdAsync(@Path("id") int id);

    // 2. Synchronous GET with Query params
    @GET("/api/users")
    User searchUsers(@Query("name") String name, @Query("role") String role);

    // 3. POST with a JSON Body
    @POST("/api/users")
    User createUser(@Body User newUser);
    
    // 4. PUT Update Request
    @PUT("/api/users/{id}")
    User updateUser(@Path("id") int id, @Body User updatedUser);

    // 5. DELETE Request returning raw String
    @DELETE("/api/users/{id}")
    String deleteUser(@Path("id") int id);
}
```

### 2. Configure Interceptors (Optional)

You can easily append behavior like Logging, Basic Authentication, or complex Async OAuth behaviors using the `Interceptor` chain.

```java
import io.declarative.http.api.interceptors.LoggingInterceptor;
import io.declarative.http.api.auth.BasicAuthInterceptor;

// Simple detailed logging for Request and Response headers/body
LoggingInterceptor logger = new LoggingInterceptor();

// Automatic Basic Auth headers
BasicAuthInterceptor auth = new BasicAuthInterceptor("admin", "secretPassword");
```

### 3. Build the Client and Execute

Pass in your native `HttpClient`, configure the base URL, add your interceptors, and dynamically create your interface implementation.

```java
import io.declarative.http.client.NativeApiClient;
import java.net.http.HttpClient;

public class Main {
    public static void main(String[] args) {
        
        // Native Java 21 HTTP Client
        HttpClient javaClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();

        // Build NativeApiClient
        NativeApiClient apiClient = new NativeApiClient.Builder()
                .baseUrl("https://api.example.com")
                .client(javaClient)
                .addInterceptor(new LoggingInterceptor())
                .addInterceptor(new BasicAuthInterceptor("user", "pass"))
                .build();

        // Create your service
        UserService service = apiClient.createService(UserService.class);

        // Execute API synchronously
        try {
            User user = service.searchUsers("Alice", "Admin");
            System.out.println("User retrieved: " + user.name());
        } catch (ApiException e) {
            // Elegant error handling matching native API exceptions
            System.err.println("API Failed with HTTP " + e.statusCode());
            if (e.errorPayload() != null) {
                System.err.println("Error Code: " + e.errorPayload().errorCode());
                System.err.println("Message: " + e.errorPayload().errorMessage());
            }
        }
    }
}
```

## 🛠 Advanced Features

### OAuth Async Interception
Handling access tokens that expire mid-flight is seamless. Use the provided `OAuthAsyncInterceptor` by providing your own `AsyncTokenManager`. The client will intercept `401 Unauthorized` responses, asynchronously refresh the token, and automatically retry the original request.

```java
AsyncTokenManager tokenManager = new CustomTokenManager(); // Implements AsyncTokenManager
OAuthAsyncInterceptor oauthInterceptor = new OAuthAsyncInterceptor(tokenManager);

NativeApiClient apiClient = new NativeApiClient.Builder()
        .baseUrl("https://api.example.com")
        .addInterceptor(oauthInterceptor)
        .build();
```

### Automatic Error Parsing
Instead of manually dealing with successful vs. failed HTTP status codes, `NativeApiClient` wraps failure HTTP responses into an `ApiException`. 
If the API returns a standardized JSON error matching `{"errorCode": "...", "errorMessage": "..."}`, it will be automatically mapped to the `errorPayload()` record inside the exception.
