package io.declarative.http.api.auth.oauth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe TokenManager that proactively refreshes the access token before it expires.
 */
public final class RefreshingTokenManager implements TokenManager, AutoCloseable {

    private static final Logger log =
            LoggerFactory.getLogger(RefreshingTokenManager.class);

    private static final Duration DEFAULT_REFRESH_THRESHOLD = Duration.ofSeconds(30);

    private final TokenFetcher fetcher;
    private final Duration refreshThreshold;
    private final AtomicReference<AccessToken> tokenRef = new AtomicReference<>();
    private final ScheduledExecutorService scheduler;

    public RefreshingTokenManager(TokenFetcher fetcher,
                                  ScheduledExecutorService scheduler) {
        this(fetcher, DEFAULT_REFRESH_THRESHOLD, scheduler);
    }

    public RefreshingTokenManager(TokenFetcher fetcher,
                                  Duration refreshThreshold,
                                  ScheduledExecutorService scheduler) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.refreshThreshold = Objects.requireNonNull(refreshThreshold, "refreshThreshold");
        this.scheduler = (scheduler != null)
                ? scheduler
                : Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "token-refresh");
            t.setDaemon(true);
            return t;
        });

        // Eagerly fetch first token
        try {
            tokenRef.set(fetcher.fetchNewToken());
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch initial access token", e);
        }

        // Background refresh every 10 seconds
        this.scheduler.scheduleAtFixedRate(
                this::refreshIfExpiringSoonSafe, 10, 10, TimeUnit.SECONDS);
    }

    @Override
    public String getAccessToken() {
        AccessToken token = tokenRef.get();
        if (token == null || token.isExpired()) {
            synchronized (this) {
                token = tokenRef.get();
                if (token == null || token.isExpired()) {
                    try {
                        token = fetcher.fetchNewToken();
                        tokenRef.set(token);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to refresh access token", e);
                    }
                }
            }
        }
        return token.value();
    }

    @Override
    public void invalidate() {
        tokenRef.set(null);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }


    private void refreshIfExpiringSoonSafe() {
        try {
            AccessToken current = tokenRef.get();
            if (current == null || current.expiresWithin(refreshThreshold)) {
                AccessToken fresh = fetcher.fetchNewToken();
                tokenRef.set(fresh);
                log.debug("Token proactively refreshed; expires at {}", fresh.expiresAt());
            }
        } catch (Exception e) {
            log.warn("Background token refresh failed (will retry in next cycle): {}",
                    e.getMessage(), e);
        }
    }
}
