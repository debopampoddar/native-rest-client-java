package io.declarative.http.api.auth;

import io.declarative.http.api.interceptors.ClientInterceptor;
import io.declarative.http.api.interceptors.InterceptorChain;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Adds an API key to a configurable request header.
 *
 * <p>The key is supplied at invocation time, allowing applications to rotate it
 * through a secret store. A {@code null} or blank key leaves the request unchanged.
 * When a key is present, it replaces any existing value for the configured header;
 * it never appends a duplicate value.
 *
 * <p>Prefer a header such as {@code X-Api-Key} over a query parameter. Query-string
 * credentials are more likely to appear in logs, browser history, and proxy traces.
 */
public final class ApiKeyAuthInterceptor implements ClientInterceptor {

    private final String headerName;
    private final Supplier<String> apiKeySupplier;

    /**
     * Creates an interceptor with a fixed API key.
     *
     * @param headerName request header that carries the API key, for example {@code X-Api-Key}
     * @param apiKey non-null API key value
     */
    public ApiKeyAuthInterceptor(String headerName, String apiKey) {
        this(headerName, fixedKeySupplier(apiKey));
    }

    /**
     * Creates an interceptor that obtains the API key for every request.
     *
     * @param headerName request header that carries the API key, for example {@code X-Api-Key}
     * @param apiKeySupplier source of the current key; may return {@code null} or blank
     */
    public ApiKeyAuthInterceptor(String headerName, Supplier<String> apiKeySupplier) {
        this.headerName = validateHeaderName(headerName);
        this.apiKeySupplier = Objects.requireNonNull(apiKeySupplier, "apiKeySupplier");
    }

    /**
     * Attaches the current API key and continues the request interceptor chain.
     *
     * @param request outgoing request
     * @param chain remaining request interceptor chain
     * @return request with the current key header when one is available
     * @throws IOException if a later interceptor throws an I/O exception
     */
    @Override
    public HttpRequest intercept(HttpRequest request, InterceptorChain chain) throws IOException {
        String apiKey = apiKeySupplier.get();
        if (apiKey == null || apiKey.isBlank()) {
            return chain.proceed(request);
        }
        HttpRequest authenticated = HttpRequest.newBuilder(request, (name, value) -> true)
                .setHeader(headerName, apiKey)
                .build();
        return chain.proceed(authenticated);
    }

    /**
     * Wraps a required fixed key in a supplier so both public constructors share
     * the same request-time code path.
     *
     * @param apiKey fixed API key
     * @return supplier for that key
     */
    private static Supplier<String> fixedKeySupplier(String apiKey) {
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        return () -> apiKey;
    }

    /**
     * Rejects header names that would be invalid or unsafe in an HTTP request.
     *
     * @param headerName candidate header name
     * @return validated header name
     */
    private static String validateHeaderName(String headerName) {
        if (headerName == null || headerName.isBlank()) {
            throw new IllegalArgumentException("headerName must not be blank");
        }
        if (headerName.indexOf('\r') >= 0 || headerName.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("headerName must not contain CR or LF");
        }
        return headerName;
    }
}
