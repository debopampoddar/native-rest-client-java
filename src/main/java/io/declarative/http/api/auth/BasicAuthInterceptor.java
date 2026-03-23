package io.declarative.http.api.auth;

import io.declarative.http.api.interceptors.RequestExecutor;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/**
 * An {@link ApiInterceptor} that automatically adds a Basic Authentication header to the request.
 *
 * @author Debopam
 */
public class BasicAuthInterceptor implements ApiInterceptor {
    private final String encodedCredentials;

    /**
     * Constructs a new {@link BasicAuthInterceptor}.
     *
     * @param username the Basic Auth username
     * @param password the Basic Auth password
     */
    public BasicAuthInterceptor(String username, String password) {
        String auth = username + ":" + password;
        this.encodedCredentials = Base64.getEncoder().encodeToString(auth.getBytes());
    }

    @Override
    public CompletableFuture<HttpResponse<String>> intercept(HttpRequest request, RequestExecutor chain) {
        // Java 16+ allows copying an existing request easily
        HttpRequest authenticatedRequest = HttpRequest.newBuilder(request, (k, v) -> true)
                .setHeader("Authorization", "Basic " + encodedCredentials)
                .build();

        return chain.execute(authenticatedRequest);
    }
}