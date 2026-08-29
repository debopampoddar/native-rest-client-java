package io.declarative.http.api.auth.oauth;

import java.time.Clock;
import java.time.Duration;
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
        return isExpired(Clock.systemUTC());
    }

    public boolean expiresWithin(Duration window) {
        return expiresWithin(window, Clock.systemUTC());
    }

    boolean isExpired(Clock clock) {
        return !Instant.now(clock).isBefore(expiresAt);
    }

    boolean expiresWithin(Duration window, Clock clock) {
        return !Instant.now(clock).isBefore(expiresAt.minus(window));
    }

    @Override
    public String toString() {
        return "AccessToken[value=[REDACTED], expiresAt=" + expiresAt + "]";
    }
}
