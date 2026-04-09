package io.declarative.http.handler;

import io.declarative.http.client.RequestContext;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public record PathHandler(String name, boolean encoded) implements ParameterHandler {

    @Override
    public void apply(RequestContext ctx, Object value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "@Path parameter '" + name + "' must not be null");
        }
        String strValue = encoded
                ? String.valueOf(value)
                : URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8)
                .replace("+", "%20"); // RFC 3986 space encoding
        ctx.replacePath("{" + name + "}", strValue);
    }
}
