package io.declarative.http.api.auth.oauth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshingTokenManagerTest {

    private final List<RefreshingTokenManager> managers = new ArrayList<>();

    private RefreshingTokenManager manager(TokenFetcher fetcher,
                                           Duration threshold,
                                           ScheduledExecutorService scheduler) {
        RefreshingTokenManager m = new RefreshingTokenManager(fetcher, threshold, scheduler);
        managers.add(m);
        return m;
    }

    @AfterEach
    void tearDown() {
        managers.forEach(RefreshingTokenManager::close);
    }

    @Test
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
    void getAccessToken_refreshesWhenExpired() {
        List<AccessToken> fetched = new ArrayList<>();
        TokenFetcher fetcher = () -> {
            if (fetched.isEmpty()) {
                // Initial token already expired
                AccessToken t = new AccessToken("expired",
                        Instant.now().minusSeconds(1));
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

        // First call should detect expired token and fetch a new one
        String token = tm.getAccessToken();

        assertThat(token).isEqualTo("fresh");
        assertThat(fetched).hasSize(2); // initial + refreshed
    }

    @Test
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
    void constructorWithNullScheduler_createsInternalScheduler() {
        TokenFetcher fetcher = () -> new AccessToken("t", Instant.now().plusSeconds(60));

        RefreshingTokenManager tm =
                manager(fetcher, Duration.ofSeconds(5), null);

        // Just call getAccessToken to ensure it works; close() will shut down internal scheduler
        assertThat(tm.getAccessToken()).isEqualTo("t");
    }
}
