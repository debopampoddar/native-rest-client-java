package io.declarative.http.client;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable, per-invocation request overrides.
 *
 * <p>Declare this as the final, unannotated parameter of a service method. Its
 * headers are applied after static and parameter headers, and therefore replace
 * headers with the same name. Request interceptors still run afterwards and may
 * apply final cross-cutting policy such as authentication.
 *
 * <pre>{@code
 * @POST("/payments")
 * Payment create(@Body PaymentRequest request, RequestOptions options);
 *
 * RequestOptions options = RequestOptions.builder()
 *         .header("X-Correlation-Id", correlationId)
 *         .header("Idempotency-Key", idempotencyKey)
 *         .timeout(Duration.ofSeconds(5))
 *         .build();
 * }</pre>
 */
public final class RequestOptions {

    private static final RequestOptions EMPTY = new RequestOptions(Map.of(), null);

    private final Map<String, String> headers;
    private final Duration timeout;

    /**
     * Creates immutable options from already-validated values.
     *
     * @param headers headers to apply, keyed case-insensitively
     * @param timeout optional replacement for the client default timeout
     */
    private RequestOptions(Map<String, String> headers, Duration timeout) {
        TreeMap<String, String> copiedHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        copiedHeaders.putAll(headers);
        this.headers = Collections.unmodifiableMap(copiedHeaders);
        this.timeout = timeout;
    }

    /**
     * Returns an options instance with no overrides.
     *
     * @return the shared empty options instance
     */
    public static RequestOptions empty() {
        return EMPTY;
    }

    /**
     * Starts construction of request options.
     *
     * @return a new mutable builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the headers to apply to this request.
     *
     * @return an immutable map of header name to value
     */
    public Map<String, String> headers() {
        return headers;
    }

    /**
     * Returns the per-request timeout override.
     *
     * @return the timeout, or {@code null} when the client default applies
     */
    public Duration timeout() {
        return timeout;
    }

    /**
     * Fluent builder for {@link RequestOptions}.
     */
    public static final class Builder {

        private final Map<String, String> headers =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private Duration timeout;

        /**
         * Adds or replaces one header. Header names are compared case-insensitively.
         *
         * @param name non-blank header name
         * @param value non-null header value
         * @return this builder
         */
        public Builder header(String name, String value) {
            validateHeader(name, value);
            headers.put(name, value);
            return this;
        }

        /**
         * Sets the timeout for this invocation.
         *
         * @param timeout positive request timeout
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.timeout = timeout;
            return this;
        }

        /**
         * Produces an immutable options instance.
         *
         * @return the configured request options
         */
        public RequestOptions build() {
            return headers.isEmpty() && timeout == null
                    ? EMPTY
                    : new RequestOptions(headers, timeout);
        }

        /**
         * Rejects invalid header values before they reach the JDK request builder.
         *
         * @param name proposed header name
         * @param value proposed header value
         */
        private static void validateHeader(String name, String value) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("header name must not be blank");
            }
            if (value == null) {
                throw new NullPointerException("header value");
            }
            if (name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("header name and value must not contain CR or LF");
            }
        }
    }
}
