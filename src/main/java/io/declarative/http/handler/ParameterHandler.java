package io.declarative.http.handler;

import io.declarative.http.client.RequestContext;

/**
 * Sealed strategy interface that binds a single method parameter to an outgoing
 * {@link RequestContext}.
 *
 * <p>Each implementation corresponds to exactly one parameter-level annotation.
 * Instances are created once per method during {@link io.declarative.http.client.ResolvedMethod}
 * parsing and reused on every subsequent call to that method.
 *
 * <p>The sealed hierarchy guarantees that only the known, library-provided
 * implementations are permitted:
 * <ul>
 *   <li>{@link BodyHandler}       — maps {@code @Body} parameters (JSON body)</li>
 *   <li>{@link FieldHandler}      — maps {@code @Field} parameters (form fields)</li>
 *   <li>{@link HeaderHandler}     — maps {@code @Header} parameters (single header)</li>
 *   <li>{@link HeaderMapHandler}  — maps {@code @HeaderMap} parameters (header batch)</li>
 *   <li>{@link PathHandler}       — maps {@code @Path} parameters (URI path variables)</li>
 *   <li>{@link QueryHandler}      — maps {@code @Query} parameters (URI query params)</li>
 *   <li>{@link QueryMapHandler}   — maps {@code @QueryMap} parameters (query param batch)</li>
 *   <li>{@link UrlHandler}        — maps {@code @Url} parameters (dynamic base URL)</li>
 * </ul>
 *
 * @see io.declarative.http.client.ResolvedMethod
 * @see RequestContext
 */
public sealed interface ParameterHandler
        permits BodyHandler, FieldHandler, HeaderHandler, HeaderMapHandler,
        PathHandler, QueryHandler, QueryMapHandler, UrlHandler {

    /**
     * Applies this parameter's contribution to the mutable request context.
     *
     * <p>Implementations may modify the URI path, append query parameters, set headers,
     * supply a request body, or add form fields. Implementations must be idempotent with
     * respect to a single invocation — the same handler instance is reused across calls.
     *
     * @param ctx   the mutable {@link RequestContext} accumulating the current request's
     *              URI, headers, query parameters, form fields, and body
     * @param value the runtime argument value supplied by the caller; may be {@code null}
     *              for optional parameters (each implementation documents its null behaviour)
     */
    void apply(RequestContext ctx, Object value);
}
