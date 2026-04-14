package io.declarative.http.handler;

import io.declarative.http.client.RequestContext;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Binds a {@link Map} method parameter annotated with
 * {@link io.declarative.http.api.annotation.QueryMap @QueryMap} to multiple URI
 * query parameters.
 *
 * <p>Each non-null map entry is appended as a separate {@code key=value} query
 * parameter. Entries with a {@code null} value are silently skipped.
 *
 * <p>Both keys and values are percent-encoded using UTF-8 by default; set
 * {@code encoded = true} on the annotation to pass them through unchanged.
 *
 * @see io.declarative.http.api.annotation.QueryMap
 * @see RequestContext#addQueryParam(String, String)
 */
public record QueryMapHandler(boolean encoded) implements ParameterHandler {

    /**
     * Iterates the map and appends each non-null entry as a query parameter.
     *
     * @param ctx   the mutable request context
     * @param value a {@link Map Map&lt;String, Object&gt;} of query parameters;
     *              {@code null} and entries with {@code null} values are silently skipped
     * @throws IllegalArgumentException if {@code value} is non-null but not a {@link Map}
     */
    @Override
    public void apply(RequestContext ctx, Object value) {
        if (value == null) return;
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "@QueryMap parameter must be of type Map<String, Object>");
        }
        map.forEach((k, v) -> {
            if (v == null) return;
            String key = encoded ? String.valueOf(k)
                    : URLEncoder.encode(String.valueOf(k), StandardCharsets.UTF_8);
            String val = encoded ? String.valueOf(v)
                    : URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8)
                      .replace("+", "%20");
            ctx.addQueryParam(key, val);
        });
    }
}
