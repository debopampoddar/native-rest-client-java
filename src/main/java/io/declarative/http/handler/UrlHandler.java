package io.declarative.http.handler;

import io.declarative.http.client.RequestContext;

/**
 * Binds a method parameter annotated with
 * {@link io.declarative.http.api.annotation.Url @Url} to the full request URL,
 * completely replacing the configured base URL and path template.
 *
 * <p>This is useful when the target URL is determined at runtime — for example
 * when following a {@code Location} header from a redirect, calling a
 * dynamically discovered microservice endpoint, or invoking a pre-signed URL.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public interface FileApi {
 *     @GET
 *     byte[] download(@Url String absoluteUrl);
 * }
 *
 * // The base URL is ignored; the supplied URL is used directly:
 * fileApi.download("https://cdn.example.com/files/report-2024.pdf");
 * }</pre>
 *
 * <p>The HTTP verb annotation ({@code @GET}, {@code @POST}, etc.) must still be present
 * on the method; its {@code value()} path is ignored when {@code @Url} is in use.
 *
 * <p><b>Null handling:</b> a {@code null} argument is silently ignored and the
 * original base URL + path template is preserved.
 *
 * @see io.declarative.http.api.annotation.Url
 * @see RequestContext#overrideFullUrl(String)
 */
public final class UrlHandler implements ParameterHandler {

    /**
     * Overrides the full request URL in the context with the supplied absolute URL.
     *
     * @param ctx   the mutable request context
     * @param value the absolute URL string to use; {@code null} is silently ignored
     */
    @Override
    public void apply(RequestContext ctx, Object value) {
        if (value != null) {
            ctx.overrideFullUrl(value.toString());
        }
    }
}
