package io.declarative.http.api.auth;

import io.declarative.http.api.interceptors.ClientInterceptor;
import io.declarative.http.api.interceptors.InterceptorChain;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Dynamically injects a Bearer token on every request.
 * Accepts a {@link Supplier} so tokens can be refreshed at call time.
 */
public final class BearerAuthInterceptor implements ClientInterceptor {

    private final Supplier<String> tokenSupplier;

    public BearerAuthInterceptor(String token) {
        Objects.requireNonNull(token, "token must not be null");
        this.tokenSupplier = () -> token;
    }

    public BearerAuthInterceptor(Supplier<String> tokenSupplier) {
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier");
    }

    @Override
    public HttpRequest intercept(HttpRequest request, InterceptorChain chain) throws IOException {
        String token = tokenSupplier.get();
        if (token == null || token.isBlank()) {
            return chain.proceed(request);
        }

        HttpRequest authenticated = HttpRequest.newBuilder(request, (k, v) -> true)
                .header("Authorization", "Bearer " + token)
                .build();
        return chain.proceed(authenticated);
    }
}
