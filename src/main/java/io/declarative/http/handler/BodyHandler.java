package io.declarative.http.handler;

import io.declarative.http.client.RequestContext;

/**
 * Binds a method parameter annotated with
 * {@link io.declarative.http.api.annotation.Body @Body} to the JSON request body.
 *
 * <p>The supplied value is stored in the {@link RequestContext} via
 * {@link RequestContext#setBody(Object)}. Actual serialisation to JSON bytes is
 * deferred until {@link RequestContext#buildRequest()} is called, at which point
 * Jackson serialises the object and sets the {@code Content-Type} header to
 * {@code application/json; charset=UTF-8}.
 *
 * <p>Only one {@code @Body} parameter is permitted per method; registering two will
 * result in the second silently overwriting the first.
 *
 * <p><b>Null handling:</b> a {@code null} body value is passed through to
 * {@link RequestContext#setBody(Object)}, which will produce a request with
 * no body publisher.
 *
 * @see io.declarative.http.api.annotation.Body
 * @see RequestContext#setBody(Object)
 */
public final class BodyHandler implements ParameterHandler {

    /**
     * Stores the body object in the request context for deferred JSON serialisation.
     *
     * @param ctx   the mutable request context
     * @param value any Jackson-serialisable object; {@code null} produces no request body
     */
    @Override
    public void apply(RequestContext ctx, Object value) {
        ctx.setBody(value);
    }
}
