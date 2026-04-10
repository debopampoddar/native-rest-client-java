package io.declarative.http.api.auth.oauth;

import java.time.Instant;
import java.util.Objects;

/**
 * Holds an OAuth 2.0 access token and its expiry time.
 * Token format/content is opaque to the client.
 */
public record AccessToken(String value, Instant expiresAt) {

    public AccessToken {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean expiresWithin(java.time.Duration window) {
        return Instant.now().isAfter(expiresAt.minus(window));
    }
}
