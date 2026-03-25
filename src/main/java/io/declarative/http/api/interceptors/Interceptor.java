package io.declarative.http.api.interceptors;

import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * An interceptor allows for observing, modifying, and short-circuiting outgoing requests or incoming responses.
 * Interceptors are powerful tools for cross-cutting concerns like logging, authentication, or caching.
 *
 * @author Debopam
 */
public interface Interceptor {
    /**
     * Intercepts the execution of an HTTP request, receiving and returning a response body as a stream.
     *
     * @param chain the interceptor chain containing the request and execution flow
     * @return a future completing with the HTTP response, where the body is an {@link InputStream}
     */
    CompletableFuture<HttpResponse<InputStream>> intercept(Chain chain);

    /**
     * Represents the execution flow of an HTTP request through multiple interceptors.
     * Each implementation of {@link #proceed(HttpRequest)} is responsible for calling the next interceptor in the chain.
     */
    interface Chain {
        /**
         * Returns the original {@link HttpRequest} for this chain.
         *
         * @return the request
         */
        HttpRequest request();

        /**
         * Proceeds with the execution of the given HTTP request.
         *
         * @param request the HTTP request to proceed with
         * @return a future completing with the HTTP response, where the body is an {@link InputStream}
         */
        CompletableFuture<HttpResponse<InputStream>> proceed(HttpRequest request);
    }
}
