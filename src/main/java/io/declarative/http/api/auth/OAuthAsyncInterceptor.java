package io.declarative.http.api.auth;

import io.declarative.http.api.interceptors.Interceptor;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * An asynchronous {@link Interceptor} that manages OAuth token authentication.
 * It automatically adds an access token and handles refreshing on a 401 response.
 *
 * @author Debopam
 */
public class OAuthAsyncInterceptor implements Interceptor {
    private final AsyncTokenManager tokenManager;

    /**
     * Constructs a new {@link OAuthAsyncInterceptor}.
     *
     * @param tokenManager the manager responsible for providing tokens asynchronously
     */
    public OAuthAsyncInterceptor(AsyncTokenManager tokenManager) {
        this.tokenManager = tokenManager;
    }

    @Override
    public CompletableFuture<HttpResponse<String>> intercept(Chain chain) {
        HttpRequest originalRequest = chain.request();

        return tokenManager.getAccessToken().thenCompose(token -> {
            HttpRequest authRequest = appendToken(originalRequest, token);

            return chain.proceed(authRequest).thenCompose(response -> {
                if (response.statusCode() == 401) {
                    System.out.println("401 detected. Refreshing token...");
                    return tokenManager.refreshAccessToken().thenCompose(newToken -> {
                        HttpRequest retryRequest = appendToken(originalRequest, newToken);
                        return chain.proceed(retryRequest);
                    });
                }
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