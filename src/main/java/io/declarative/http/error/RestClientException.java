package io.declarative.http.error;

/**
 * Wraps internal framework failures (misconfiguration, serialization errors, etc.).
 */
public final class RestClientException extends RuntimeException {

    public RestClientException(String message) {
        super(message);
    }

    public RestClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
