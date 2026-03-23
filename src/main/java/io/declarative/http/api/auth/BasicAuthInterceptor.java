package io.declarative.http.api.auth;

import io.declarative.http.api.interceptors.RequestExecutor;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

public class BasicAuthInterceptor implements ApiInterceptor {
    private final String encodedCredentials;

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
