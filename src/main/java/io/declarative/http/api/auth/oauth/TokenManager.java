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

    /**
     * Clears the cached token so the next access obtains a replacement.
     * Implementations must make this operation safe with concurrent access.
     */
    void invalidate();

    /**
     * Returns a token suitable for retrying a request rejected with {@code rejectedToken}.
     * Implementations should reuse a newer cached token and serialize refreshes.
     *
     * <p>The default preserves compatibility for simple implementations by invalidating
     * and reacquiring a token. Production managers should override it to avoid duplicate
     * refreshes when several requests receive the same 401 response concurrently.
     *
     * @param rejectedToken token value that received the 401; may be {@code null}
     * @return a current token, possibly equal to the rejected token when no replacement
     *         is available
     */
    default String refreshAfterRejection(String rejectedToken) {
        invalidate();
        return getAccessToken();
    }
}
