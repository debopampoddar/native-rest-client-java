package io.declarative.http.api.auth.oauth;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Holds an OAuth 2.0 access token and its expiry time.
 * Token format/content is opaque to the client.
 */
public final class AccessToken {

    private final String value;
    private final Instant expiresAt;

    public AccessToken(String value, Instant expiresAt) {
        this.value = Objects.requireNonNull(value, "value");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public String value() {
        return value;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    /**
     * Returns true if the token will be expired at or before the given instant.
     */
    public boolean isExpiredAt(Instant instant) {
        return !expiresAt.isAfter(instant);
    }

    /**
     * Returns true if the token is already expired.
     */
    public boolean isExpired() {
        return isExpiredAt(Instant.now());
    }

    /**
     * Returns true if the token will expire within the given safety window.
     * This allows proactive refresh before actual expiry to avoid 401s.
     */
    public boolean isExpiringWithin(Duration window) {
        Instant now = Instant.now();
        return isExpiredAt(now.plus(window));
    }

    @Override
    public String toString() {
        return "AccessToken{expiresAt=" + expiresAt + '}';
    }
}
