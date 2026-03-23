package io.declarative.http.api.auth;

import io.declarative.http.api.interceptors.RequestExecutor;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

public class OAuthInterceptor implements ApiInterceptor {
    private final AsyncTokenManager tokenManager; // Your custom token manager

    public OAuthInterceptor(AsyncTokenManager tokenManager) {
        this.tokenManager = tokenManager;
    }

    @Override
    public CompletableFuture<HttpResponse<String>> intercept(HttpRequest request, RequestExecutor chain) {
        // 1. Get the current token asynchronously
        return tokenManager.getAccessToken().thenCompose(token -> {

            // 2. Add token and execute
            HttpRequest authRequest = appendToken(request, token);
            return chain.execute(authRequest).thenCompose(response -> {

                // 3. Handle 401 Unauthorized
                if (response.statusCode() == 401) {
                    // Refresh token asynchronously, then retry the request
                    return tokenManager.refreshAccessToken().thenCompose(newToken -> {
                        HttpRequest retryRequest = appendToken(request, newToken);
                        return chain.execute(retryRequest);
                    });
                }

                // Return successful response
                return CompletableFuture.completedFuture(response);
            });
        });
    }

    private HttpRequest appendToken(HttpRequest request, String token) {
        return HttpRequest.newBuilder(request, (k, v) -> true)
                .setHeader("Authorization", "Bearer " + token)
                .build();
    }
}

