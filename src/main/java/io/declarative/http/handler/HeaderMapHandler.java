package io.declarative.http.handler;

import io.declarative.http.client.RequestContext;

import java.util.Map;

public final class HeaderMapHandler implements ParameterHandler {

    @Override
    public void apply(RequestContext ctx, Object value) {
        if (value == null) return;

        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "@HeaderMap parameter must be of type Map<String, String>");
        }

        map.forEach((k, v) -> {
            if (k != null && v != null) {
                ctx.addHeader(String.valueOf(k), String.valueOf(v));
            }
        });
    }
}
