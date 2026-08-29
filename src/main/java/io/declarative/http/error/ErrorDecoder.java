package io.declarative.http.error;

import java.net.http.HttpHeaders;

/**
 * Converts an HTTP error response into an exception meaningful to the calling
 * application.
 *
 * <p>Configure an implementation through
 * {@link io.declarative.http.client.NativeRestClient.Builder#errorDecoder(ErrorDecoder)}.
 * It is invoked for non-envelope responses with a 4xx or 5xx status after the
 * response body has been read and closed. Implementations must not return
 * {@code null} and should avoid logging credentials or response content that
 * may contain sensitive data.
 */
@FunctionalInterface
public interface ErrorDecoder {

    /**
     * Produces the exception that will be thrown to the service-method caller.
     *
     * @param status HTTP response status code
     * @param headers immutable response headers
     * @param body UTF-8 response body, truncated to the client's safety limit;
     *             may be empty or {@code "<unreadable>"}
     * @return a non-null exception
     */
    RuntimeException decode(int status, HttpHeaders headers, String body);

    /**
     * Returns the library's default status-and-body error mapping.
     *
     * @return a decoder that creates {@link ApiException} instances
     */
    static ErrorDecoder apiException() {
        return (status, headers, body) -> new ApiException(status, body);
    }
}
