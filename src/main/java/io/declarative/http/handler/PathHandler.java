package io.declarative.http.handler;

import io.declarative.http.client.RequestContext;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Binds a method parameter annotated with
 * {@link io.declarative.http.api.annotation.Path @Path} to a URI path variable.
 *
 * <p>The handler locates the {@code {name}} placeholder in the current resolved URL
 * and replaces it with the string representation of the runtime argument value.
 * By default, the value is percent-encoded using UTF-8 (RFC 3986) with spaces
 * rendered as {@code %20} rather than {@code +}.
 *
 * <p><b>Null safety:</b> a {@code null} argument causes an
 * {@link IllegalArgumentException} to be thrown at call time because leaving a path
 * variable unresolved would produce a malformed URI.
 *
 * @see io.declarative.http.api.annotation.Path
 * @see RequestContext#replacePath(String, String)
 */
public record PathHandler(String name, boolean encoded) implements ParameterHandler {

    /**
     * Substitutes the {@code {name}} placeholder in the request URL with the
     * (optionally encoded) string value of the argument.
     *
     * @param ctx   the mutable request context
     * @param value the path variable value; must not be {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    @Override
    public void apply(RequestContext ctx, Object value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "@Path parameter '" + name + "' must not be null");
        }
        String strValue = encoded
                ? String.valueOf(value)
                : URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8)
                  .replace("+", "%20");
        ctx.replacePath("{" + name + "}", strValue);
    }
}
