package io.declarative.http.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.net.http.HttpHeaders;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderSanitizerTest {

    @Test
    @DisplayName("Sanitize redacts sensitive headers case insensitive")
    void sanitize_redactsSensitiveHeaders_caseInsensitive() {
        Map<String, List<String>> raw = Map.of(
                "Authorization", List.of("Bearer secret-token"),
                "X-Api-Key", List.of("key-123"),
                "Cookie", List.of("session=abc"),
                "Set-Cookie", List.of("session=abc; HttpOnly"),
                "Proxy-Authorization", List.of("Basic abc=="),
                "X-Custom", List.of("visible")
        );

        HttpHeaders headers = HttpHeaders.of(raw, (k, v) -> true);

        Map<String, List<String>> sanitized = HeaderSanitizer.sanitize(headers);

        assertThat(sanitized.get("Authorization")).containsExactly("[REDACTED]");
        assertThat(sanitized.get("X-Api-Key")).containsExactly("[REDACTED]");
        assertThat(sanitized.get("Cookie")).containsExactly("[REDACTED]");
        assertThat(sanitized.get("Set-Cookie")).containsExactly("[REDACTED]");
        assertThat(sanitized.get("Proxy-Authorization")).containsExactly("[REDACTED]");

        // Non-sensitive header passes through unchanged
        assertThat(sanitized.get("X-Custom")).containsExactly("visible");
    }

    @Test
    @DisplayName("Sanitize leaves empty headers map untouched")
    void sanitize_leavesEmptyHeadersMapUntouched() {
        HttpHeaders headers = HttpHeaders.of(Map.of(), (k, v) -> true);
        Map<String, List<String>> sanitized = HeaderSanitizer.sanitize(headers);
        assertThat(sanitized).isEmpty();
    }

    @Test
    @DisplayName("Sanitize uri removes user info query and fragment")
    void sanitizeUri_removesUserInfoQueryAndFragment() {
        URI uri = URI.create("https://user:password@example.test:8443/path"
                + "?access_token=secret#private");

        assertThat(HeaderSanitizer.sanitize(uri))
                .isEqualTo("https://example.test:8443/path")
                .doesNotContain("secret", "password", "private");
    }
}
