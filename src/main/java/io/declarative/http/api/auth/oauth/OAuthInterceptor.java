package io.declarative.http.api.auth.oauth;

import io.declarative.http.api.interceptors.ClientInterceptor;
import io.declarative.http.api.interceptors.InterceptorChain;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.util.Objects;

/**
 * Adds an OAuth 2.0 access token to the Authorization header.
 *
 * By default uses the "Bearer" token type:
 *   Authorization: Bearer &lt;access_token&gt;
 *
 * The TokenManager is responsible for obtaining and refreshing tokens
 * according to OAuth 2.0 best practices [web:80][web:107][web:113].
 */
public final class OAuthInterceptor implements ClientInterceptor {

    private final TokenManager tokenManager;
    private final String tokenType;

    /**
     * Creates an OAuth interceptor with token type "Bearer".
     */
    public OAuthInterceptor(TokenManager tokenManager) {
        this(tokenManager, "Bearer");
    }

    /**
     * Creates an OAuth interceptor with a custom token type
     * (e.g. "Bearer", "Token", "MAC").
     */
    public OAuthInterceptor(TokenManager tokenManager, String tokenType) {
        this.tokenManager = Objects.requireNonNull(tokenManager, "tokenManager");
        this.tokenType = Objects.requireNonNull(tokenType, "tokenType");
    }

    @Override
    public HttpRequest intercept(HttpRequest request, InterceptorChain chain) throws IOException {
        String token = tokenManager.getAccessToken();
        if (token == null || token.isBlank()) {
            // No token available; pass through unchanged.
            return chain.proceed(request);
        }

        String headerValue = tokenType + " " + token;
        HttpRequest authenticated = HttpRequest.newBuilder(request, (k, v) -> true)
                .header("Authorization", headerValue)
                .build();

        return chain.proceed(authenticated);
    }
}
