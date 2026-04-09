package io.declarative.http.handler;

import io.declarative.http.client.RequestContext;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public record QueryMapHandler(boolean encoded) implements ParameterHandler {

    @Override
    public void apply(RequestContext ctx, Object value) {
        if (value == null) return;

        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "@QueryMap parameter must be of type Map<String, Object>");
        }

        map.forEach((k, v) -> {
            if (v == null) return;
            String key = encoded
                    ? String.valueOf(k)
                    : URLEncoder.encode(String.valueOf(k), StandardCharsets.UTF_8);
            String val = encoded
                    ? String.valueOf(v)
                    : URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8)
                    .replace("+", "%20");
            ctx.addQueryParam(key, val);
        });
    }
}
