package io.declarative.http.api.interceptors;

import io.declarative.http.api.auth.oauth.TokenManager;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Handles one OAuth2 Bearer challenge by obtaining a replacement token and replaying
 * the request once.
 *
 * <p>A replay occurs only for a 401 response with a {@code WWW-Authenticate: Bearer}
 * challenge, only when a distinct nonblank token is available, and never for 403. The
 * rejected response body is closed before replaying. The implementation supports both
 * synchronous and asynchronous exchange chains.
 */

public final class TokenRefreshExchangeInterceptor
        implements HttpExchangeInterceptor, AsyncHttpExchangeInterceptor {

    private static final Pattern BEARER_CHALLENGE =
            Pattern.compile("(?i)(?:^|,)\\s*Bearer(?:\\s|$)");

    private final Supplier<String> accessTokenSupplier;
    private final Runnable refreshToken; // triggers token refresh, could be more elaborate
    private final TokenManager tokenManager;

    /**
     * @deprecated Prefer {@link #TokenRefreshExchangeInterceptor(TokenManager)} so
     * token comparison and refresh concurrency are handled atomically.
     * @param accessTokenSupplier source of the replacement token
     * @param refreshToken action that refreshes the token source
     */
    @Deprecated
    public TokenRefreshExchangeInterceptor(Supplier<String> accessTokenSupplier,
                                           Runnable refreshToken) {
        this.accessTokenSupplier = Objects.requireNonNull(accessTokenSupplier, "accessTokenSupplier");
        this.refreshToken = Objects.requireNonNull(refreshToken, "refreshToken");
        this.tokenManager = null;
    }

    /**
     * Creates an authenticator backed by a concurrency-safe token manager.
     *
     * @param tokenManager source of the current token and rejected-token refresh policy
     */
    public TokenRefreshExchangeInterceptor(TokenManager tokenManager) {
        this.tokenManager = Objects.requireNonNull(tokenManager, "tokenManager");
        this.accessTokenSupplier = tokenManager::getAccessToken;
        this.refreshToken = tokenManager::invalidate;
    }

    @Override
    public <T> HttpResponse<T> intercept(HttpRequest request,
                                         ExchangeChain<T> chain)
            throws IOException, InterruptedException {

        // First attempt, with whatever token is currently present
        HttpResponse<T> response = chain.proceed(request);
        if (!shouldRefresh(response)) {
            return response;
        }

        String rejected = bearerToken(request);
        String candidate = refreshAfterRejection(rejected);
        if (candidate == null || candidate.isBlank() || candidate.equals(rejected)) {
            return response;
        }

        ResponseBodies.close(response);
        return chain.proceed(withBearerToken(request, candidate));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
            HttpRequest request, AsyncExchangeChain<T> chain) {
        return chain.proceed(request).thenCompose(response -> {
            if (!shouldRefresh(response)) {
                return CompletableFuture.completedFuture(response);
            }
            String rejected = bearerToken(request);
            String candidate;
            try {
                candidate = refreshAfterRejection(rejected);
                if (candidate == null || candidate.isBlank() || candidate.equals(rejected)) {
                    return CompletableFuture.completedFuture(response);
                }
                ResponseBodies.close(response);
            } catch (RuntimeException | IOException e) {
                return CompletableFuture.failedFuture(e);
            }
            return chain.proceed(withBearerToken(request, candidate));
        });
    }

    private String refreshAfterRejection(String rejected) {
        if (tokenManager != null) {
            return tokenManager.refreshAfterRejection(rejected);
        }
        refreshToken.run();
        return accessTokenSupplier.get();
    }

    private static boolean shouldRefresh(HttpResponse<?> response) {
        return response.statusCode() == 401
                && response.headers().allValues("WWW-Authenticate").stream()
                .anyMatch(value -> BEARER_CHALLENGE.matcher(value).find());
    }

    private static String bearerToken(HttpRequest request) {
        String value = request.headers().firstValue("Authorization").orElse(null);
        if (value == null || value.length() < 7
                || !value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return value.substring(7).trim();
    }

    private static HttpRequest withBearerToken(HttpRequest request, String token) {
        return HttpRequest.newBuilder(request, (name, value) -> true)
                .setHeader("Authorization", "Bearer " + token)
                .build();
    }
}
