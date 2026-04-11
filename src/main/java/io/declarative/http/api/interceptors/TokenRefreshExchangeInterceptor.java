package io.declarative.http.api.interceptors;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.Supplier;

/**
 * On a 401 response, refresh the OAuth token and retry once transparently, then propagate failure if it still fails.
 */

public final class TokenRefreshExchangeInterceptor implements HttpExchangeInterceptor {

    private final Supplier<String> accessTokenSupplier;
    private final Runnable refreshToken; // triggers token refresh, could be more elaborate

    public TokenRefreshExchangeInterceptor(Supplier<String> accessTokenSupplier,
                                           Runnable refreshToken) {
        this.accessTokenSupplier = accessTokenSupplier;
        this.refreshToken = refreshToken;
    }

    @Override
    public <T> HttpResponse<T> intercept(HttpRequest request,
                                         ExchangeChain<T> chain)
            throws IOException, InterruptedException {

        // First attempt, with whatever token is currently present
        HttpResponse<T> response = chain.proceed(request);
        if (response.statusCode() != 401) {
            return response;
        }

        // 401: refresh token and retry once
        refreshToken.run();

        HttpRequest retried = HttpRequest.newBuilder(request, (name, value) -> !name.equalsIgnoreCase("Authorization"))
                .header("Authorization", "Bearer " + accessTokenSupplier.get())
                .build();

        return chain.proceed(retried);
    }
}
