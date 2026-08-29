package io.declarative.http.handler;

import io.declarative.http.client.RequestContext;
import io.declarative.http.client.RequestOptions;

/**
 * Applies an unannotated final {@link RequestOptions} service-method argument.
 */
public final class RequestOptionsHandler implements ParameterHandler {

    /**
     * Applies options when present and ignores a {@code null} options argument.
     *
     * @param context mutable request accumulator
     * @param value request options, or {@code null}
     * @throws IllegalArgumentException if the parser contract is violated at runtime
     */
    @Override
    public void apply(RequestContext context, Object value) {
        if (value == null) {
            return;
        }
        if (!(value instanceof RequestOptions options)) {
            throw new IllegalArgumentException("RequestOptions parameter must be RequestOptions");
        }
        context.applyOptions(options);
    }
}
