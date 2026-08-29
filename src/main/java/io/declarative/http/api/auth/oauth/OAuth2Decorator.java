package io.declarative.http.api.auth.oauth;

import io.declarative.http.api.interceptors.TokenRefreshExchangeInterceptor;
import io.declarative.http.client.NativeRestClient;

import java.util.Objects;

/**
 * Wires one {@link TokenManager} into OAuth2 request attachment and 401 Bearer
 * challenge handling.
 *
 * <p>The decorator deliberately knows nothing about a grant type or token endpoint.
 * Those concerns remain behind {@link TokenFetcher} and {@link TokenManager}. The
 * same manager is used for the request interceptor and the reactive authenticator,
 * preventing them from observing different token state.
 *
 * <p>This class does not own the token manager. If the supplied manager is
 * {@link AutoCloseable}, the application that created it remains responsible for
 * closing it.
 */
public final class OAuth2Decorator {

    private final TokenManager tokenManager;

    private OAuth2Decorator(TokenManager tokenManager) {
        this.tokenManager = Objects.requireNonNull(tokenManager, "tokenManager");
    }

    /**
     * Creates an OAuth2 decorator for one shared token manager.
     *
     * @param tokenManager token state used for attachment and refresh
     * @return a decorator ready to apply to a client builder
     */
    public static OAuth2Decorator with(TokenManager tokenManager) {
        return new OAuth2Decorator(tokenManager);
    }

    /**
     * Adds OAuth2 attachment and Bearer-challenge handling to a client builder.
     * The built-in authenticator participates in both synchronous and asynchronous
     * exchange chains.
     *
     * @param builder client builder to decorate
     * @return the same builder for continued configuration
     */
    public NativeRestClient.Builder applyTo(NativeRestClient.Builder builder) {
        TokenRefreshExchangeInterceptor authenticator =
                new TokenRefreshExchangeInterceptor(tokenManager);
        return builder
                .addInterceptor(new OAuthInterceptor(tokenManager::getAccessToken))
                .addExchangeInterceptor(authenticator);
    }
}
