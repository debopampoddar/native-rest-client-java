package io.declarative.http.handler;

import io.declarative.http.client.RequestContext;
import java.util.Map;

/**
 * Binds a {@link Map} method parameter annotated with
 * {@link io.declarative.http.api.annotation.HeaderMap @HeaderMap} to multiple HTTP
 * request headers.
 *
 * <p>Each map entry whose key and value are both non-null is added as an individual
 * request header. This is useful for passing a dynamic set of headers determined at
 * call time (e.g. tracing context, forwarded headers).
 *
 * @see io.declarative.http.api.annotation.HeaderMap
 * @see RequestContext#addHeader(String, String)
 */
public final class HeaderMapHandler implements ParameterHandler {

    /**
     * Iterates the map and adds each non-null entry as a request header.
     *
     * @param ctx   the mutable request context
     * @param value a {@link Map Map&lt;String, String&gt;} of header name-value pairs;
     *              {@code null} map or entries with {@code null} keys/values are ignored
     * @throws IllegalArgumentException if {@code value} is non-null but not a {@link Map}
     */
    @Override
    public void apply(RequestContext ctx, Object value) {
        if (value == null) return;
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "@HeaderMap parameter must be of type Map<String, String>");
        }
        map.forEach((k, v) -> {
            if (k != null && v != null) {
                ctx.addHeader(String.valueOf(k), String.valueOf(v));
            }
        });
    }
}
