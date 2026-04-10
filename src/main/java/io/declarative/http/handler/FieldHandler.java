package io.declarative.http.handler;

import io.declarative.http.client.RequestContext;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Applies a single {@link io.declarative.http.api.annotation.Field} parameter
 * to the in-progress {@link RequestContext} for {@code @FormUrlEncoded} requests.
 *
 * <p>The field value is percent-encoded with UTF-8 (unless {@code encoded = true})
 * and accumulated via {@link RequestContext#addFormField(String, String)}.
 * {@link RequestContext#buildRequest()} later joins all fields with {@code &}
 * and writes them as the {@code application/x-www-form-urlencoded} body.
 *
 * <pre>
 *   RestClientException: Parameter N of 'login' has no recognised annotation
 * </pre>
 */
public final class FieldHandler implements ParameterHandler {

    private final String name;
    private final boolean encoded;

    /**
     * @param name    the form field name (value of the {@code @Field} annotation)
     * @param encoded {@code true} if the caller guarantees the value is already
     *                percent-encoded; {@code false} to apply UTF-8 percent-encoding
     */
    public FieldHandler(String name, boolean encoded) {
        this.name    = Objects.requireNonNull(name, "Field name must not be null");
        this.encoded = encoded;
    }

    /**
     * Adds this field to the request context.
     * Null values are silently skipped (form fields are optional by convention).
     */
    @Override
    public void apply(RequestContext ctx, Object value) {
        if (value == null) {
            return; // silently omit null form fields
        }
        String raw = value.toString();
        String encodedValue = encoded
                ? raw
                : URLEncoder.encode(raw, StandardCharsets.UTF_8)
                .replace("+", "%20"); // RFC 3986: spaces as %20, not +
        ctx.addFormField(name, encodedValue);
    }
}
