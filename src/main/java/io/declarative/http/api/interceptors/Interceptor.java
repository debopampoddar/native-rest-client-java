package io.declarative.http.api.interceptors;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * An interface for intercepting API requests to modify them before execution.
 *
 * @author Debopam
 */
public interface Interceptor {
    /**
     * Intercepts the HTTP request, optionally modifies it, and passes it to the next executor in the chain.
     *
     * @param chain the interceptor chain containing the request and execution flow
     * @return a future completing with the HTTP response
     */
    CompletableFuture<HttpResponse<String>> intercept(Chain chain);

    /**
     * Represents the execution flow of an HTTP request through multiple interceptors.
     */
    interface Chain {
        /**
         * Returns the current HTTP request.
         *
         * @return the request
         */
        HttpRequest request();

        /**
         * Proceeds to the next interceptor in the chain or executes the actual network call.
         *
         * @param request the HTTP request to proceed with
         * @return a future completing with the HTTP response
         */
        CompletableFuture<HttpResponse<String>> proceed(HttpRequest request);
    }
}