package io.declarative.http.example;

import io.declarative.http.api.auth.BearerAuthInterceptor;
import io.declarative.http.api.interceptors.LoggingInterceptor;
import io.declarative.http.api.interceptors.RetryInterceptor;
import io.declarative.http.client.NativeRestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class Main {

    public static void main(String[] args) throws Exception {

        NativeRestClient client = NativeRestClient
                .builder("https://api.example.com")
                //.connectTimeout(Duration.ofSeconds(5))
                .addInterceptor(new LoggingInterceptor())
                .addInterceptor(new BearerAuthInterceptor(() -> getAccessToken()))
                .addInterceptor(new RetryInterceptor(3, 500L))
                .build();

        UserService api = client.create(UserService.class);

        // ---- Synchronous calls ----
        User user = api.getUser(42L);
        System.out.println("Fetched: " + user.name());

        List<User> page1 = api.listUsers(0, 20, "name,asc");
        System.out.println("Total users on page: " + page1.size());

        Map<String, Object> filters = Map.of(
                "role", "admin", "active", "true", "country", "US"
        );
        List<User> admins = api.searchUsers(filters);

        api.deleteUser(42L, "Account closure requested");

        // ---- Async call ----
        CompletableFuture<User> future = api.getUserAsync(99L);
        future.thenAccept(u -> System.out.println("Async result: " + u.name()))
                .exceptionally(ex -> { System.err.println("Error: " + ex.getMessage()); return null; });

        // ---- Dynamic headers ----
        Map<String, String> traceHeaders = Map.of(
                "X-Request-ID", "abc-123",
                "X-Trace-ID",   "span-456"
        );
        User traced = api.getUserWithHeaders(42L, traceHeaders);

        future.join(); // wait for async result before exit
    }

    private static String getAccessToken() {
        // Call your token provider / cache
        return "eyJhbGci...";
    }
}
