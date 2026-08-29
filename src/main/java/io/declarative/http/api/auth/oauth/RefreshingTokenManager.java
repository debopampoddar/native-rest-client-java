package io.declarative.http.api.auth.oauth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe {@link TokenManager} that acquires a token eagerly, refreshes it before
 * expiry, and serializes every refresh path through one lock.
 *
 * <p>Calls to {@link #getAccessToken()} honor {@code refreshThreshold}; a token inside
 * that window is refreshed before it is returned. {@link #refreshAfterRejection(String)}
 * compares the rejected token with the cached token, so concurrent 401 responses can reuse
 * a token refreshed by another request instead of starting a refresh storm.
 *
 * <p>If {@code scheduler} is {@code null}, this manager creates and owns a daemon scheduler.
 * {@link #close()} always cancels its periodic refresh task, but shuts down the scheduler only
 * when it created that scheduler. Caller-supplied schedulers remain caller-owned.
 */
public final class RefreshingTokenManager implements TokenManager, AutoCloseable {

    private static final Logger log =
            LoggerFactory.getLogger(RefreshingTokenManager.class);

    private static final Duration DEFAULT_REFRESH_THRESHOLD = Duration.ofSeconds(30);

    private final TokenFetcher fetcher;
    private final Duration refreshThreshold;
    private final AtomicReference<AccessToken> tokenRef = new AtomicReference<>();
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final Clock clock;
    private final ReentrantLock refreshLock = new ReentrantLock();
    private final ScheduledFuture<?> refreshTask;

    /**
     * Creates a manager using the default 30-second refresh threshold.
     *
     * @param fetcher source of valid access tokens
     * @param scheduler scheduler for periodic renewal, or {@code null} for an internal daemon scheduler
     */
    public RefreshingTokenManager(TokenFetcher fetcher,
                                  ScheduledExecutorService scheduler) {
        this(fetcher, DEFAULT_REFRESH_THRESHOLD, scheduler);
    }

    /**
     * Creates a manager with a configurable early-refresh threshold.
     *
     * @param fetcher source of valid access tokens
     * @param refreshThreshold duration before expiry at which the token is refreshed; must not be negative
     * @param scheduler scheduler for periodic renewal, or {@code null} for an internal daemon scheduler
     * @throws RuntimeException if initial token acquisition fails or produces a blank/expired token
     */
    public RefreshingTokenManager(TokenFetcher fetcher,
                                  Duration refreshThreshold,
                                  ScheduledExecutorService scheduler) {
        this(fetcher, refreshThreshold, scheduler, Clock.systemUTC());
    }

    RefreshingTokenManager(TokenFetcher fetcher,
                           Duration refreshThreshold,
                           ScheduledExecutorService scheduler,
                           Clock clock) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.refreshThreshold = Objects.requireNonNull(refreshThreshold, "refreshThreshold");
        if (refreshThreshold.isNegative()) {
            throw new IllegalArgumentException("refreshThreshold must not be negative");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        AccessToken initialToken = fetchToken("Failed to fetch initial access token");

        this.ownsScheduler = scheduler == null;
        this.scheduler = ownsScheduler
                ? Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "token-refresh");
            t.setDaemon(true);
            return t;
        })
                : scheduler;

        tokenRef.set(initialToken);

        // Background refresh every 10 seconds
        try {
            this.refreshTask = this.scheduler.scheduleAtFixedRate(
                    this::refreshIfExpiringSoonSafe, 10, 10, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            if (ownsScheduler) {
                this.scheduler.shutdownNow();
            }
            throw e;
        }
    }

    @Override
    public String getAccessToken() {
        AccessToken token = tokenRef.get();
        if (isUsable(token)) {
            return token.value();
        }
        return refreshIfCurrent(token).value();
    }

    @Override
    public void invalidate() {
        refreshLock.lock();
        try {
            tokenRef.set(null);
        } finally {
            refreshLock.unlock();
        }
    }

    @Override
    public String refreshAfterRejection(String rejectedToken) {
        refreshLock.lock();
        try {
            AccessToken current = tokenRef.get();
            if (current != null
                    && !current.value().equals(rejectedToken)
                    && !current.isExpired(clock)) {
                return current.value();
            }
            AccessToken fresh = fetchToken("Failed to refresh access token");
            tokenRef.set(fresh);
            return fresh.value();
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * Cancels periodic renewal and shuts down only an internally-created scheduler.
     * This method is safe to call more than once.
     */
    @Override
    public void close() {
        refreshTask.cancel(false);
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    private void refreshIfExpiringSoonSafe() {
        try {
            AccessToken observed = tokenRef.get();
            if (!isUsable(observed)) {
                AccessToken fresh = refreshIfCurrent(observed);
                log.debug("Token proactively refreshed; expires at {}", fresh.expiresAt());
            }
        } catch (Exception e) {
            log.warn("Background token refresh failed; will retry: {}",
                    e.getClass().getSimpleName());
        }
    }

    private boolean isUsable(AccessToken token) {
        return token != null && !token.expiresWithin(refreshThreshold, clock);
    }

    private AccessToken refreshIfCurrent(AccessToken observed) {
        refreshLock.lock();
        try {
            AccessToken current = tokenRef.get();
            if (current != observed && isUsable(current)) {
                return current;
            }
            AccessToken fresh = fetchToken("Failed to refresh access token");
            tokenRef.set(fresh);
            return fresh;
        } finally {
            refreshLock.unlock();
        }
    }

    private AccessToken fetchToken(String message) {
        try {
            AccessToken token = Objects.requireNonNull(
                    fetcher.fetchNewToken(), "TokenFetcher returned null");
            if (token.value().isBlank()) {
                throw new IllegalStateException("TokenFetcher returned a blank access token");
            }
            if (token.isExpired(clock)) {
                throw new IllegalStateException("TokenFetcher returned an expired access token");
            }
            return token;
        } catch (Exception e) {
            throw new RuntimeException(message, e);
        }
    }
}
