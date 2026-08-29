package io.declarative.http.api.auth.oauth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshingTokenManagerTest {

    private final List<RefreshingTokenManager> managers = new ArrayList<>();
    private final List<ScheduledExecutorService> schedulers = new ArrayList<>();

    private RefreshingTokenManager manager(TokenFetcher fetcher,
                                           Duration threshold,
                                           ScheduledExecutorService scheduler) {
        RefreshingTokenManager m = new RefreshingTokenManager(fetcher, threshold, scheduler);
        managers.add(m);
        if (scheduler != null) {
            schedulers.add(scheduler);
        }
        return m;
    }

    @AfterEach
    void tearDown() {
        managers.forEach(RefreshingTokenManager::close);
        schedulers.forEach(ScheduledExecutorService::shutdownNow);
    }

    @Test
    @DisplayName("Get access token fetches initial token and caches")
    void getAccessToken_fetchesInitialTokenAndCaches() {
        List<AccessToken> fetched = new ArrayList<>();
        TokenFetcher fetcher = () -> {
            AccessToken t = new AccessToken("token-1", Instant.now().plusSeconds(60));
            fetched.add(t);
            return t;
        };

        RefreshingTokenManager tm = manager(fetcher,
                Duration.ofSeconds(30), Executors.newSingleThreadScheduledExecutor());

        String first = tm.getAccessToken();
        String second = tm.getAccessToken();

        assertThat(first).isEqualTo("token-1");
        assertThat(second).isEqualTo("token-1");
        // One fetch in constructor, no extra fetch during getAccessToken
        assertThat(fetched).hasSize(1);
    }

    @Test
    @DisplayName("Get access token refreshes when within threshold")
    void getAccessToken_refreshesWhenWithinThreshold() {
        List<AccessToken> fetched = new ArrayList<>();
        TokenFetcher fetcher = () -> {
            if (fetched.isEmpty()) {
                AccessToken t = new AccessToken("expiring",
                        Instant.now().plusSeconds(5));
                fetched.add(t);
                return t;
            }
            AccessToken t = new AccessToken("fresh",
                    Instant.now().plusSeconds(60));
            fetched.add(t);
            return t;
        };

        RefreshingTokenManager tm = manager(fetcher,
                Duration.ofSeconds(30), Executors.newSingleThreadScheduledExecutor());

        // First call should honor the skew and fetch a new token.
        String token = tm.getAccessToken();

        assertThat(token).isEqualTo("fresh");
        assertThat(fetched).hasSize(2); // initial + refreshed
    }

    @Test
    @DisplayName("Invalidate clears token and forces refetch")
    void invalidate_clearsTokenAndForcesRefetch() {
        List<AccessToken> fetched = new ArrayList<>();
        TokenFetcher fetcher = () -> {
            AccessToken t = new AccessToken("v" + fetched.size(),
                    Instant.now().plusSeconds(60));
            fetched.add(t);
            return t;
        };

        RefreshingTokenManager tm = manager(fetcher,
                Duration.ofSeconds(30), Executors.newSingleThreadScheduledExecutor());

        String first = tm.getAccessToken(); // v0
        tm.invalidate();
        String second = tm.getAccessToken(); // v1

        assertThat(first).isEqualTo("v0");
        assertThat(second).isEqualTo("v1");
        assertThat(fetched).hasSize(2);
    }

    @Test
    @DisplayName("Constructor with null scheduler creates internal scheduler")
    void constructorWithNullScheduler_createsInternalScheduler() {
        TokenFetcher fetcher = () -> new AccessToken("t", Instant.now().plusSeconds(60));

        RefreshingTokenManager tm =
                manager(fetcher, Duration.ofSeconds(5), null);

        // Just call getAccessToken to ensure it works; close() will shut down internal scheduler
        assertThat(tm.getAccessToken()).isEqualTo("t");
    }

    @Test
    @DisplayName("Concurrent near expiry access performs single refresh")
    void concurrentNearExpiryAccess_performsSingleRefresh() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        TokenFetcher fetcher = () -> fetches.incrementAndGet() == 1
                ? new AccessToken("expiring", Instant.now().plusSeconds(5))
                : new AccessToken("fresh", Instant.now().plusSeconds(300));
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        RefreshingTokenManager tm = manager(fetcher, Duration.ofSeconds(30), scheduler);

        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                results.add(callers.submit(tm::getAccessToken));
            }
            for (Future<String> result : results) {
                assertThat(result.get()).isEqualTo("fresh");
            }
        }

        assertThat(fetches).hasValue(2);
    }

    @Test
    @DisplayName("Concurrent rejections perform single refresh")
    void concurrentRejections_performSingleRefresh() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        TokenFetcher fetcher = () -> new AccessToken(
                fetches.incrementAndGet() == 1 ? "old" : "fresh",
                Instant.now().plusSeconds(300));
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        RefreshingTokenManager tm = manager(fetcher, Duration.ofSeconds(30), scheduler);

        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                results.add(callers.submit(() -> tm.refreshAfterRejection("old")));
            }
            for (Future<String> result : results) {
                assertThat(result.get()).isEqualTo("fresh");
            }
        }

        assertThat(fetches).hasValue(2);
    }

    @Test
    @DisplayName("Close does not shutdown caller owned scheduler")
    void close_doesNotShutdownCallerOwnedScheduler() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        RefreshingTokenManager tm = manager(
                () -> new AccessToken("token", Instant.now().plusSeconds(300)),
                Duration.ofSeconds(30), scheduler);

        tm.close();

        assertThat(scheduler.isShutdown()).isFalse();
    }

    @Test
    @DisplayName("Constructor rejects blank token")
    void constructor_rejectsBlankToken() {
        assertThatThrownBy(() -> new RefreshingTokenManager(
                () -> new AccessToken(" ", Instant.now().plusSeconds(300)),
                Duration.ofSeconds(30), null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("initial access token");
    }

    @Test
    @DisplayName("Constructor rejects expired token")
    void constructor_rejectsExpiredToken() {
        assertThatThrownBy(() -> new RefreshingTokenManager(
                () -> new AccessToken("expired", Instant.now().minusSeconds(1)),
                Duration.ofSeconds(30), null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("initial access token");
    }
}
