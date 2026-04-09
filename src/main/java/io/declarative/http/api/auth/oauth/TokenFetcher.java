package io.declarative.http.api.auth.oauth;

/**
 * Strategy for obtaining a fresh access token from the authorization server.
 * Implementation typically performs an HTTP call using a refresh token,
 * client credentials, or any other grant type.
 */
@FunctionalInterface
public interface TokenFetcher {

    /**
     * Fetches a new access token (and expiry) from the authorization server.
     */
    AccessToken fetchNewToken();
}
