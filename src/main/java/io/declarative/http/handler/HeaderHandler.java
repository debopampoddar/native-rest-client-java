package io.declarative.http.handler;

import io.declarative.http.client.RequestContext;

public record HeaderHandler(String name) implements ParameterHandler {

    @Override
    public void apply(RequestContext ctx, Object value) {
        if (value == null) return;
        ctx.addHeader(name, String.valueOf(value));
    }
}
