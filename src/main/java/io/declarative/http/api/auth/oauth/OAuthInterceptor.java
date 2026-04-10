package io.declarative.http.api.auth.oauth;

import io.declarative.http.api.interceptors.ClientInterceptor;
import io.declarative.http.api.interceptors.InterceptorChain;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Adds {@code Authorization: Bearer <access_token>} (or custom token type) per RFC 6750.
 * Token is obtained from a Supplier so it can be refreshed dynamically.
 */
public final class OAuthInterceptor implements ClientInterceptor {

    private final Supplier<String> accessTokenSupplier;
    private final String tokenType;

    public OAuthInterceptor(Supplier<String> accessTokenSupplier) {
        this(accessTokenSupplier, "Bearer");
    }

    public OAuthInterceptor(Supplier<String> accessTokenSupplier, String tokenType) {
        this.accessTokenSupplier = Objects.requireNonNull(accessTokenSupplier, "accessTokenSupplier");
        this.tokenType = Objects.requireNonNull(tokenType, "tokenType");
    }

    @Override
    public HttpRequest intercept(HttpRequest request, InterceptorChain chain)
            throws IOException {
        String token = accessTokenSupplier.get();
        if (token == null || token.isBlank()) {
            return chain.proceed(request);
        }
        HttpRequest authed = HttpRequest.newBuilder(request, (k, v) -> true)
                .header("Authorization", tokenType + " " + token)
                .build();
        return chain.proceed(authed);
    }
}
