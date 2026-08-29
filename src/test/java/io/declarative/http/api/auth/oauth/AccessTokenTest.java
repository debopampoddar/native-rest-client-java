package io.declarative.http.api.auth.oauth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AccessTokenTest {

    @Test
    @DisplayName("Is expired true when expires in past")
    void isExpired_trueWhenExpiresInPast() {
        AccessToken token = new AccessToken("t1", Instant.now().minusSeconds(10));
        assertThat(token.isExpired()).isTrue();
    }

    @Test
    @DisplayName("Is expired false when expires in future")
    void isExpired_falseWhenExpiresInFuture() {
        AccessToken token = new AccessToken("t2", Instant.now().plusSeconds(60));
        assertThat(token.isExpired()).isFalse();
    }

    @Test
    @DisplayName("Expires within true when within window")
    void expiresWithin_trueWhenWithinWindow() {
        AccessToken token = new AccessToken("t3", Instant.now().plusSeconds(5));
        assertThat(token.expiresWithin(Duration.ofSeconds(10))).isTrue();
    }

    @Test
    @DisplayName("Expires within false when outside window")
    void expiresWithin_falseWhenOutsideWindow() {
        AccessToken token = new AccessToken("t4", Instant.now().plusSeconds(60));
        assertThat(token.expiresWithin(Duration.ofSeconds(10))).isFalse();
    }

    @Test
    @DisplayName("To string redacts token value")
    void toString_redactsTokenValue() {
        AccessToken token = new AccessToken("super-secret-token", Instant.EPOCH);

        assertThat(token.toString())
                .doesNotContain("super-secret-token")
                .contains("[REDACTED]");
    }
}
