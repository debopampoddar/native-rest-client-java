package io.declarative.http.client;

import java.net.http.HttpHeaders;

/**
 * A transparent wrapper around an HTTP response that exposes the raw status code,
 * response headers, and deserialised body together.
 *
 * <p>When a service interface method declares a return type of
 * {@code HttpResponseEnvelope<T>}, the {@link InvocationDispatcher} never throws
 * {@link io.declarative.http.error.ApiException} — even for 4xx and 5xx responses.
 * Instead, the caller receives the full response and can inspect the status code
 * directly to implement custom error handling, conditional logic, or response
 * introspection.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public interface UserApi {
 *     @DELETE("/users/{id}")
 *     HttpResponseEnvelope<Void> deleteUser(@Path("id") long id);
 *
 *     @GET("/users/{id}")
 *     HttpResponseEnvelope<User> findUser(@Path("id") long id);
 * }
 *
 * HttpResponseEnvelope<User> env = userApi.findUser(42L);
 * if (env.isSuccessful()) {
 *     process(env.body());
 * } else {
 *     log.warn("Unexpected status: {}", env.status());
 * }
 * }</pre>
 *
 * <p>This is a record; all three components are stored as canonical record components
 * and are accessible via the generated accessor methods {@link #status()},
 * {@link #headers()}, and {@link #body()}.
 *
 * @param <T>     the deserialised body type; use {@link Void} for responses with no body
 * @param status  the raw HTTP status code, e.g. {@code 200}, {@code 404}
 * @param headers the full set of response headers returned by the server
 * @param body    the deserialised response body, or {@code null} for 204 / void responses
 *
 * @see InvocationDispatcher
 * @see ResolvedMethod#wrapInEnvelope()
 */
public record HttpResponseEnvelope<T>(int status,
                                      HttpHeaders headers,
                                      T body) {

    /**
     * Returns {@code true} if the HTTP status code indicates a successful response,
     * i.e. if {@code status} is in the range {@code [200, 300)}.
     *
     * @return {@code true} for 2xx status codes
     */
    public boolean isSuccessful() {
        return status >= 200 && status < 300;
    }
}
