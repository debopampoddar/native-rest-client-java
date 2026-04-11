package io.declarative.http.api.auth.oauth;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AccessTokenTest {

    @Test
    void isExpired_trueWhenExpiresInPast() {
        AccessToken token = new AccessToken("t1", Instant.now().minusSeconds(10));
        assertThat(token.isExpired()).isTrue();
    }

    @Test
    void isExpired_falseWhenExpiresInFuture() {
        AccessToken token = new AccessToken("t2", Instant.now().plusSeconds(60));
        assertThat(token.isExpired()).isFalse();
    }

    @Test
    void expiresWithin_trueWhenWithinWindow() {
        AccessToken token = new AccessToken("t3", Instant.now().plusSeconds(5));
        assertThat(token.expiresWithin(Duration.ofSeconds(10))).isTrue();
    }

    @Test
    void expiresWithin_falseWhenOutsideWindow() {
        AccessToken token = new AccessToken("t4", Instant.now().plusSeconds(60));
        assertThat(token.expiresWithin(Duration.ofSeconds(10))).isFalse();
    }
}
