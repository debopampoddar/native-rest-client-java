package io.declarative.http.security;

import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderSanitizerTest {

    @Test
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
    void sanitize_leavesEmptyHeadersMapUntouched() {
        HttpHeaders headers = HttpHeaders.of(Map.of(), (k, v) -> true);
        Map<String, List<String>> sanitized = HeaderSanitizer.sanitize(headers);
        assertThat(sanitized).isEmpty();
    }
}
