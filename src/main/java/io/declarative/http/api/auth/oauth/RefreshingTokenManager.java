package io.declarative.http.api.auth.oauth;

import io.declarative.http.error.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe TokenManager that:
 *  - Caches the current access token in memory.
 *  - Refreshes it on-demand when near expiry.
 *  - Optionally refreshes periodically in the background.
 *
 * This follows OAuth 2.0 best practices of short-lived access tokens paired
 * with longer-lived refresh tokens handled over a back channel [web:107][web:111][web:113].
 */
public final class RefreshingTokenManager implements TokenManager, AutoCloseable {

    private final TokenFetcher tokenFetcher;
    private final Duration refreshSkew;
    private final ScheduledExecutorService scheduler; // may be null

    // Volatile so readers see fresh value without locking
    private volatile AccessToken currentToken;

    // Used as monitor for refreshing
    private final Object lock = new Object();

    /**
     * Creates a manager with on-demand refresh only.
     *
     * @param tokenFetcher function that obtains a new token from the auth server
     * @param refreshSkew time before expiry when token should be proactively refreshed
     */
    public RefreshingTokenManager(TokenFetcher tokenFetcher, Duration refreshSkew) {
        this(tokenFetcher, refreshSkew, null, Duration.ofSeconds(30));
    }

    /**
     * Creates a manager with optional periodic refresh.
     *
     * @param tokenFetcher function that obtains a new token from the auth server
     * @param refreshSkew  time before expiry when token should be considered "expiring soon"
     * @param scheduler    optional external scheduler; if null, an internal one is created
     * @param checkInterval how often the scheduler checks for expiry
     */
    public RefreshingTokenManager(TokenFetcher tokenFetcher,
                                  Duration refreshSkew,
                                  ScheduledExecutorService scheduler,
                                  Duration checkInterval) {
        this.tokenFetcher = Objects.requireNonNull(tokenFetcher, "tokenFetcher");
        this.refreshSkew = Objects.requireNonNull(refreshSkew, "refreshSkew");

        if (scheduler != null) {
            this.scheduler = scheduler;
        } else {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory());
        }

        if (checkInterval != null && !checkInterval.isZero() && !checkInterval.isNegative()) {
            // Background periodic refresh to minimize 401s
            this.scheduler.scheduleAtFixedRate(
                    this::refreshIfExpiringSoonSafe,
                    0,
                    checkInterval.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        }
    }

    @Override
    public String getAccessToken() {
        AccessToken token = currentToken;

        // Fast path: token exists and not expiring soon
        if (token != null && !token.isExpiringWithin(refreshSkew)) {
            return token.value();
        }

        // Slow path: acquire lock and refresh if still needed
        synchronized (lock) {
            token = currentToken;
            if (token == null || token.isExpiringWithin(refreshSkew)) {
                currentToken = fetchNewToken();
                token = currentToken;
            }
        }
        return token.value();
    }

    private AccessToken fetchNewToken() {
        try {
            AccessToken token = tokenFetcher.fetchNewToken();
            if (token == null || token.value() == null || token.value().isBlank()) {
                throw new RestClientException("TokenFetcher returned null or empty token");
            }
            if (token.expiresAt() == null || token.expiresAt().isBefore(Instant.now())) {
                throw new RestClientException("TokenFetcher returned already-expired token: " + token);
            }
            return token;
        } catch (Exception e) {
            throw new RestClientException("Failed to fetch new access token", e);
        }
    }

    /**
     * Called periodically by the scheduler; must not throw.
     */
    private void refreshIfExpiringSoonSafe() {
        try {
            AccessToken token = currentToken;
            if (token == null || token.isExpiringWithin(refreshSkew)) {
                synchronized (lock) {
                    token = currentToken;
                    if (token == null || token.isExpiringWithin(refreshSkew)) {
                        currentToken = fetchNewToken();
                    }
                }
            }
        } catch (Exception ignored) {
            // Suppress to avoid killing the scheduler thread; caller should log if needed.
        }
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "token-refresh-scheduler");
            t.setDaemon(true);
            return t;
        }
    }
}
