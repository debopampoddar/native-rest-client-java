package io.declarative.http.api.interceptors;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.util.List;

/**
 * Immutable chain node. Each call to {@code proceed()} advances to
 * the next interceptor, or returns the terminal request if exhausted.
 */
public final class InterceptorChain {

    private final List<ClientInterceptor> interceptors;
    private final int index;

    public InterceptorChain(List<ClientInterceptor> interceptors) {
        this(interceptors, 0);
    }

    InterceptorChain(List<ClientInterceptor> interceptors, int index) {
        this.interceptors = interceptors;
        this.index = index;
    }

    /**
     * Passes the request to the next interceptor, or returns it unchanged
     * if this is the terminal node.
     */
    public HttpRequest proceed(HttpRequest request) throws IOException {
        if (index >= interceptors.size()) return request;
        return interceptors.get(index)
                .intercept(request, new InterceptorChain(interceptors, index + 1));
    }
}
