package io.declarative.http.error;

/**
 * An exception thrown when an HTTP API call fails.
 * It contains the HTTP status code, the parsed error payload (if available), and the raw response body.
 *
 * @author Debopam
 */
public class ApiException extends RuntimeException {
    private final int statusCode;
    private final String responseBody;

    public ApiException(int statusCode, String responseBody) {
        super("HTTP " + statusCode + ": " + responseBody);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public boolean isClientError() {
        return statusCode >= 400 && statusCode < 500;
    }

    public boolean isServerError() {
        return statusCode >= 500;
    }
}
