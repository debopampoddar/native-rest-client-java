package io.declarative.http.api.interceptors;

import java.io.IOException;
import java.net.http.HttpRequest;

/**
 * Functional interface for request/response middleware.
 * Interceptors are applied in registration order.
 *
 * Example usages: auth token injection, logging, retry, circuit breaker.
 */
@FunctionalInterface
public interface ClientInterceptor {
    /**
     * @param request the current request
     * @param chain   the remaining chain; call {@code chain.proceed(request)} to continue
     * @return a (possibly modified) HttpRequest passed to the next interceptor or the actual client
     */
    HttpRequest intercept(HttpRequest request, InterceptorChain chain) throws IOException;
}

