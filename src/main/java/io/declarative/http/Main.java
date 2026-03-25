package io.declarative.http;

import io.declarative.http.api.auth.AsyncTokenManager;
import io.declarative.http.api.auth.BasicAuthInterceptor;
import io.declarative.http.api.auth.OAuthAsyncInterceptor;
import io.declarative.http.api.converters.JacksonConverter;
import io.declarative.http.api.interceptors.LoggingInterceptor;
import io.declarative.http.client.NativeApiClient;
import io.declarative.http.error.ApiException;
import io.declarative.http.example.User;
import io.declarative.http.example.UserService;

import java.net.http.HttpClient;
import java.util.concurrent.CompletableFuture;
import io.declarative.http.client.NativeApiClient;
import java.net.http.HttpClient;

/**
 * The entry point for the example application demonstrating the usage of {@link NativeApiClient}.
 *
 * @author Debopam
 */
public class Main {
    public static void main(String[] args) {

        // Native Java 21 HTTP Client
        HttpClient javaClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();

        // Build NativeApiClient
        NativeApiClient apiClient = new NativeApiClient.Builder()
                .baseUrl("[https://api.example.com](https://api.example.com)")
                .client(javaClient)
                .converter(new JacksonConverter()) // Pluggable serialization
                .addInterceptor(new LoggingInterceptor())
                .addInterceptor(new BasicAuthInterceptor("user", "pass"))
                .build();

        // Create your service
        UserService userService = apiClient.createService(UserService.class);

        // Execute API
        try {
            CompletableFuture<Void> user = userService.getUserById(42).thenAccept(_user -> {
                System.out.println("Async User loaded: " + _user.name());
            }).exceptionally(ex -> {
                System.err.println("Failed: " + ex.getMessage());
                throw new RuntimeException(ex);
            });
        } catch (ApiException e) {
            System.err.println("API Failed with HTTP " + e.statusCode());
            if (e.errorPayload() != null) {
                System.err.println("Error Code: " + e.errorPayload().errorCode());
            }
        }
    }
}
