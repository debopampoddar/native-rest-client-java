package io.declarative.http.security;

import java.net.URI;
import java.net.URISyntaxException;
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

    /**
     * Returns a URI representation safe for logs by removing user info, query,
     * and fragment components.
     *
     * @param uri URI that may contain sensitive user info, query parameters, or fragments
     * @return a scheme/host/path representation suitable for diagnostics
     */
    public static String sanitize(URI uri) {
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                    uri.getPath(), null, null).toString();
        } catch (URISyntaxException | IllegalArgumentException e) {
            return uri.getPath() == null ? "" : uri.getPath();
        }
    }
}
