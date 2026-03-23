package io.declarative.http.error;

/**
 * A record representing a standard error payload returned by the API.
 *
 * @param errorCode    the specific error code
 * @param errorMessage a descriptive error message
 * @author Debopam
 */
public record ApiErrorPayload(String errorCode,
                              String errorMessage) {
}
