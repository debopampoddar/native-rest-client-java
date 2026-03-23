package io.declarative.http;

import io.declarative.http.api.auth.AsyncTokenManager;
import io.declarative.http.api.auth.OAuthAsyncInterceptor;
import io.declarative.http.api.interceptors.LoggingInterceptor;
import io.declarative.http.client.NativeApiClient;
import io.declarative.http.example.UserService;

import java.net.http.HttpClient;
import java.util.concurrent.CompletableFuture;

/**
 * The entry point for the example application demonstrating the usage of {@link NativeApiClient}.
 *
 * @author Debopam
 */
public class Main {
    /**
     * The main method that configures the HTTP client, creates the service, and executes API calls.
     *
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        // Dummy Token Manager for the example
        AsyncTokenManager myTokenManager = new AsyncTokenManager() {
            @Override
            public CompletableFuture<String> getAccessToken() {
                return CompletableFuture.completedFuture("expired_token");
            }
            @Override
            public CompletableFuture<String> refreshAccessToken() {
                // Simulate network delay for refresh
                return CompletableFuture.supplyAsync(() -> "new_valid_token_123");
            }
        };
        // 1. Configure the native Java 21 HttpClient (e.g., adding timeouts, HTTP/2)
        HttpClient javaClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();

        // 2. Build our Retrofit replacement
        NativeApiClient apiClient = new NativeApiClient.Builder()
                .baseUrl("https://api.example.com")
                .client(javaClient)
                .addInterceptor(new LoggingInterceptor())         // Log first
                .addInterceptor(new OAuthAsyncInterceptor(myTokenManager)) // Auth second
                .build();

        // 3. Create the service
        UserService userService = apiClient.createService(UserService.class);

        // 4. Execute calls smoothly
        userService.getUserById(42).thenAccept(user -> {
            System.out.println("Async User loaded: " + user.name());
        }).exceptionally(ex -> {
            System.err.println("Failed: " + ex.getMessage());
            return null;
        });
    }
}