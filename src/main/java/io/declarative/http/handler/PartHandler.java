package io.declarative.http.handler;

import io.declarative.http.client.RequestContext;

import java.util.Objects;

/**
 * Adds one runtime argument as a named multipart contribution.
 */
public final class PartHandler implements ParameterHandler {

    private final String name;

    /**
     * Creates a handler for one {@code @Part} name.
     *
     * @param name declared multipart field name
     */
    public PartHandler(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /**
     * Adds the value to the request context; {@code null} values are omitted.
     *
     * @param context mutable request accumulator
     * @param value scalar or multipart-binary value
     */
    @Override
    public void apply(RequestContext context, Object value) {
        context.addMultipartPart(name, value);
    }
}
