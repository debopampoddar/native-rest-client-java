package io.declarative.http.api.auth;

import java.util.concurrent.CompletableFuture;

/**
 * An interface defining a manager responsible for handling authentication tokens asynchronously.
 *
 * @author Debopam
 */
public interface AsyncTokenManager {
    /**
     * Retrieves the current access token asynchronously.
     *
     * @return a future completing with the current access token
     */
    CompletableFuture<String> getAccessToken();

    /**
     * Refreshes and retrieves a new access token asynchronously.
     *
     * @return a future completing with the refreshed access token
     */
    CompletableFuture<String> refreshAccessToken();
}