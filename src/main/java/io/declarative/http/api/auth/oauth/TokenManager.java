package io.declarative.http.api.auth.oauth;

/**
 * Abstraction for managing OAuth 2.0 access tokens, including refresh.
 * Implementations must be thread-safe.
 */
public interface TokenManager {

    /**
     * Returns a valid access token, refreshing if necessary.
     * Implementations may block while performing a refresh.
     */
    String getAccessToken();
}
