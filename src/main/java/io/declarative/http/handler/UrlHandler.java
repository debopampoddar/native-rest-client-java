package io.declarative.http.handler;

import io.declarative.http.client.RequestContext;

/**
 * Applies a {@link io.declarative.http.api.annotation.Url} parameter to the
 * in-progress {@link RequestContext}, replacing the entire base URL + path template
 * with the absolute URL supplied at call time.
 *
 * <p>This is useful when the full URL is determined at runtime — for example,
 * when following a {@code Location} header from a previous response or when
 * calling a dynamically discovered service endpoint.
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * @GET
 * CompletableFuture<Resource> fetch(@Url String absoluteUrl);
 *
 * // Called as:
 * api.fetch("https://cdn.example.com/v2/resources/abc").join();
 * }</pre>
 *
 * <p>The HTTP verb annotation ({@code @GET}, {@code @POST}, etc.) must still be
 * present on the method; its {@code value()} is ignored when {@code @Url} is provided.
 *
 * <pre>
 *   RestClientException: Parameter 0 of 'fetch' has no recognised annotation
 * </pre>
 */
public final class UrlHandler implements ParameterHandler {

    /**
     * Overrides the full URL (base + path) in the request context.
     * Null values are silently ignored — the original path template is preserved.
     */
    @Override
    public void apply(RequestContext ctx, Object value) {
        if (value != null) {
            ctx.overrideFullUrl(value.toString());
        }
    }
}