package io.declarative.http.api.auth;

import io.declarative.http.api.auth.oauth.TokenManager;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Simple TokenManager for tests: holds a mutable token string.
 */
public final class FakeTokenManager implements TokenManager {

    private final AtomicReference<String> tokenRef = new AtomicReference<>();

    public FakeTokenManager(String initialToken) {
        this.tokenRef.set(initialToken);
    }

    public void setToken(String newToken) {
        this.tokenRef.set(newToken);
    }

    @Override
    public String getAccessToken() {
        return tokenRef.get();
    }

    @Override
    public void invalidate() {
        tokenRef.set(null);
    }
}
