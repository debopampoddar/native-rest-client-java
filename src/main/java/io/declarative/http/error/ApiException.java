package io.declarative.http.error;

/**
 * An exception thrown when an HTTP API call fails.
 * It contains the HTTP status code, the parsed error payload (if available), and the raw response body.
 *
 * @author Debopam
 */
public class ApiException extends RuntimeException {
    private final int statusCode;
    private final ApiErrorPayload errorPayload;
    private final String rawBody;

    /**
     * Constructs a new {@link ApiException}.
     *
     * @param statusCode   the HTTP status code of the error response
     * @param errorPayload the parsed error payload, or null if parsing failed or is unavailable
     * @param rawBody      the raw string body of the error response
     */
    public ApiException(int statusCode, ApiErrorPayload errorPayload, String rawBody) {
        super("HTTP " + statusCode + ": " + (errorPayload != null ? errorPayload.errorMessage() : "Unknown Error"));
        this.statusCode = statusCode;
        this.errorPayload = errorPayload;
        this.rawBody = rawBody;
    }

    /**
     * Returns the HTTP status code of the error response.
     *
     * @return the status code
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * Returns the parsed error payload from the response, if available.
     *
     * @return the error payload, or null
     */
    public ApiErrorPayload errorPayload() {
        return errorPayload;
    }

    /**
     * Returns the raw string body of the error response.
     *
     * @return the raw response body
     */
    public String rawBody() {
        return rawBody;
    }
}
