package io.declarative.http.handler;

import io.declarative.http.client.RequestContext;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Binds a method parameter annotated with
 * {@link io.declarative.http.api.annotation.Field @Field} to a single form field
 * in an {@code application/x-www-form-urlencoded} request body.
 *
 * <p>This handler is only meaningful on methods also annotated with
 * {@link io.declarative.http.api.annotation.FormUrlEncoded @FormUrlEncoded}. The
 * field value is percent-encoded using UTF-8 (spaces as {@code %20} per RFC 3986)
 * unless {@code encoded = true} is set on the annotation, indicating that the caller
 * has already encoded the value.
 *
 * <p>All accumulated form fields are joined with {@code &} and written as the request
 * body by {@link RequestContext#buildRequest()}.
 *
 * <p><b>Null handling:</b> {@code null} values are silently skipped — the field is
 * simply omitted from the form body, which is consistent with the convention that
 * form fields are optional.
 *
 * @see io.declarative.http.api.annotation.Field
 * @see io.declarative.http.api.annotation.FormUrlEncoded
 * @see RequestContext#addFormField(String, String)
 */
public final class FieldHandler implements ParameterHandler {

    private final String name;
    private final boolean encoded;

    /**
     * Constructs a {@code FieldHandler} for the given form field name.
     *
     * @param name    the form field name as declared in the {@code @Field} annotation;
     *                must not be {@code null}
     * @param encoded {@code true} if the caller guarantees the value is already
     *                percent-encoded; {@code false} to apply UTF-8 percent-encoding
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public FieldHandler(String name, boolean encoded) {
        this.name    = Objects.requireNonNull(name, "Field name must not be null");
        this.encoded = encoded;
    }

    /**
     * Appends this form field to the request context, encoding the value if required.
     *
     * @param ctx   the mutable request context
     * @param value the field value; {@code null} values are silently omitted
     */
    @Override
    public void apply(RequestContext ctx, Object value) {
        if (value == null) return;
        String raw = value.toString();
        String encodedValue = encoded
                ? raw
                : URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
        ctx.addFormField(name, encodedValue);
    }
}
