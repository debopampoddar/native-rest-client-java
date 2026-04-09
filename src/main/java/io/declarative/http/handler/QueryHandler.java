package io.declarative.http.handler;

import io.declarative.http.client.RequestContext;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

public record QueryHandler(String name, boolean encoded) implements ParameterHandler {

    @Override
    public void apply(RequestContext ctx, Object value) {
        if (value == null) return; // skip null query params

        if (value instanceof Collection<?> collection) {
            // Support multi-value: ?tag=java&tag=spring
            for (Object item : collection) {
                addSingle(ctx, String.valueOf(item));
            }
        } else {
            addSingle(ctx, String.valueOf(value));
        }
    }

    private void addSingle(RequestContext ctx, String raw) {
        String valueToAppend = this.encoded
                ? raw
                : URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
        ctx.addQueryParam(name, valueToAppend);
    }
}
