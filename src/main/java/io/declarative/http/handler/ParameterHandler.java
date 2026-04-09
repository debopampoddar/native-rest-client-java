package io.declarative.http.handler;


import io.declarative.http.client.RequestContext;

/**
 * Sealed interface representing a single parameter binding strategy.
 * Each implementation handles exactly one annotation type.
 * Instances are created ONCE per method at client creation time.
 */
public sealed interface ParameterHandler
         permits PathHandler, QueryHandler, QueryMapHandler,
        HeaderHandler, HeaderMapHandler, BodyHandler {

    /**
     * Applies this handler's effect to the given request context.
     * @param ctx   mutable request context accumulating URI/headers/body
     * @param value the resolved runtime argument value
     */
    void apply(RequestContext ctx, Object value);
}
