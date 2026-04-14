package io.declarative.http.handler;

import io.declarative.http.client.RequestContext;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

/**
 * Binds a method parameter annotated with
 * {@link io.declarative.http.api.annotation.Query @Query} to a URI query parameter.
 *
 * <p>If the runtime value is a {@link Collection}, each element is appended as a
 * separate {@code name=value} pair, producing multi-value query strings such as
 * {@code ?tag=java&tag=spring}.
 *
 * <p><b>Null handling:</b> a {@code null} value is silently skipped — the query
 * parameter is simply omitted from the URI.
 *
 * <p>Values are percent-encoded using UTF-8 by default (spaces become {@code %20});
 * set {@code encoded = true} on the annotation to opt out of encoding.
 *
 * @see io.declarative.http.api.annotation.Query
 * @see RequestContext#addQueryParam(String, String)
 */
public record QueryHandler(String name, boolean encoded) implements ParameterHandler {

    /**
     * Appends this query parameter (or multiple values for collections) to the
     * request context.
     *
     * @param ctx   the mutable request context
     * @param value the query parameter value; {@code null} is silently ignored;
     *              {@link Collection} instances expand to multiple name=value pairs
     */
    @Override
    public void apply(RequestContext ctx, Object value) {
        if (value == null) return;
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                addSingle(ctx, String.valueOf(item));
            }
        } else {
            addSingle(ctx, String.valueOf(value));
        }
    }

    /**
     * Appends a single {@code name=encodedValue} pair to the request context.
     *
     * @param ctx the mutable request context
     * @param raw the raw (unencoded) string value to append
     */
    private void addSingle(RequestContext ctx, String raw) {
        String valueToAppend = this.encoded
                ? raw
                : URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
        ctx.addQueryParam(name, valueToAppend);
    }
}
