package io.declarative.http.security;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility to redact sensitive HTTP headers before logging.
 * NEVER log raw Authorization, Cookie, or API key headers.
 */
public final class HeaderSanitizer {

    private static final Set<String> REDACTED_HEADERS = Set.of(
            "authorization",
            "x-api-key",
            "x-auth-token",
            "cookie",
            "set-cookie",
            "proxy-authorization"
    );

    private HeaderSanitizer() {}

    /**
     * Returns a sanitized view of the headers map, safe for logging.
     */
    public static Map<String, List<String>> sanitize(HttpHeaders headers) {
        return headers.map().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> REDACTED_HEADERS.contains(e.getKey().toLowerCase())
                                ? List.of("[REDACTED]")
                                : e.getValue()
                ));
    }
}
