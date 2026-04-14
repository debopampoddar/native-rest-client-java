package io.declarative.http.error;

/**
 * Thrown by {@link io.declarative.http.client.InvocationDispatcher} when the server
 * responds with a 4xx or 5xx HTTP status code and the method return type is not
 * {@link io.declarative.http.client.HttpResponseEnvelope}.
 *
 * <p>This exception carries both the numeric HTTP status code and the raw response
 * body text, allowing callers to implement status-specific error handling without
 * parsing the response themselves:
 *
 * <pre>{@code
 * try {
 *     User user = userApi.getUser(99L);
 * } catch (ApiException e) {
 *     if (e.getStatus() == 404) {
 *         // handle not-found
 *     } else if (e.getStatus() >= 500) {
 *         // handle server error
 *     }
 *     log.error("API error {}: {}", e.getStatus(), e.getResponseBody());
 * }
 * }</pre>
 *
 * <p>To avoid this exception entirely for a specific method, declare its return type
 * as {@code HttpResponseEnvelope<T>}.
 *
 * @see io.declarative.http.client.HttpResponseEnvelope
 * @see RestClientException
 */
public class ApiException extends RuntimeException {

    /** The HTTP response status code (e.g. 400, 401, 404, 500). */
    private final int status;

    /** The raw response body text; may be empty or {@code "<unreadable>"}. */
    private final String responseBody;

    /**
     * Constructs an {@code ApiException} with the given HTTP status code and
     * raw response body.
     *
     * @param status       the HTTP response status code
     * @param responseBody the raw response body returned by the server; must not
     *                     be {@code null} (use an empty string if the body is absent)
     */
    public ApiException(int status, String responseBody) {
        super("HTTP " + status + ": " + responseBody);
        this.status       = status;
        this.responseBody = responseBody;
    }

    /**
     * Returns the HTTP status code that triggered this exception.
     *
     * @return the response status code, e.g. {@code 404} or {@code 500}
     */
    public int getStatusCode() {
        return status;
    }

    /**
     * Returns the raw response body text included in the server's error response.
     *
     * <p>The value is the full response body read as a UTF-8 string. If the body
     * could not be read, this method returns the literal string {@code "<unreadable>"}.
     *
     * @return the response body string; never {@code null}
     */
    public String getResponseBody() {
        return responseBody;
    }
}
