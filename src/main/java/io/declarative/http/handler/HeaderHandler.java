package io.declarative.http.handler;

import io.declarative.http.client.RequestContext;

/**
 * Binds a method parameter annotated with
 * {@link io.declarative.http.api.annotation.Header @Header} to a single HTTP
 * request header.
 *
 * <p>The header value is the {@link String#valueOf(Object)} representation of the
 * runtime argument. If the value is {@code null}, the header is silently omitted.
 *
 * @see io.declarative.http.api.annotation.Header
 * @see RequestContext#addHeader(String, String)
 */
public record HeaderHandler(String name) implements ParameterHandler {

    /**
     * Adds the named header to the request context using the runtime argument as
     * the value.
     *
     * @param ctx   the mutable request context
     * @param value the header value; {@code null} values are silently ignored
     */
    @Override
    public void apply(RequestContext ctx, Object value) {
        if (value == null) return;
        ctx.addHeader(name, String.valueOf(value));
    }
}
