package io.declarative.http.handler;

import io.declarative.http.client.RequestContext;

public final class BodyHandler implements ParameterHandler {

    @Override
    public void apply(RequestContext ctx, Object value) {
        ctx.setBody(value); // serialization deferred to RequestContext.buildRequest()
    }
}
