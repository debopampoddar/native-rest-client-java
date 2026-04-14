package io.declarative.http.error;

/**
 * Thrown to indicate a client-side configuration or infrastructure failure that is
 * unrelated to the HTTP status code returned by the server.
 *
 * <p>Examples of conditions that produce a {@code RestClientException}:
 * <ul>
 *   <li>The service interface is not an interface or declares no methods.</li>
 *   <li>A method parameter lacks a recognised binding annotation
 *       ({@code @Path}, {@code @Query}, {@code @Body}, etc.).</li>
 *   <li>An unresolved path variable is detected when building the request URI.</li>
 *   <li>The request interceptor chain throws an {@link java.io.IOException}.</li>
 *   <li>The response body cannot be deserialised into the declared return type.</li>
 *   <li>No {@link io.declarative.http.api.converters.ResponseConverter} is registered
 *       for the method's return type.</li>
 * </ul>
 *
 * <p>In contrast to {@link ApiException}, which signals a well-formed HTTP error
 * response from the server, {@code RestClientException} represents a programming
 * error or an unrecoverable infrastructure problem.
 *
 * @see ApiException
 */
public class RestClientException extends RuntimeException {

    /**
     * Constructs a {@code RestClientException} with the given detail message.
     *
     * @param message a human-readable description of the failure
     */
    public RestClientException(String message) {
        super(message);
    }

    /**
     * Constructs a {@code RestClientException} with the given detail message and
     * root cause.
     *
     * @param message a human-readable description of the failure
     * @param cause   the underlying exception that caused this failure
     */
    public RestClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
