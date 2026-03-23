package io.declarative.http.api.auth;

import java.util.concurrent.CompletableFuture;


public interface AsyncTokenManager {
    CompletableFuture<String> getAccessToken();
    CompletableFuture<String> refreshAccessToken();
}
