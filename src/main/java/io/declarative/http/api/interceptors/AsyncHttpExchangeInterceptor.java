package io.declarative.http.api.interceptors;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * Intercepts a complete asynchronous HTTP exchange.
 *
 * <p>Implementations may inspect or replace the request, retry a completed
 * response, or attach observation logic. They must return the future from the
 * next chain stage (or an equivalent future) and must close a response body
 * before discarding that response to retry it.
 *
 * <p>Register an async-only implementation with
 * {@link io.declarative.http.client.NativeRestClient.Builder#addAsyncExchangeInterceptor}.
 * Built-in interceptors that implement both exchange interfaces are registered for
 * the async chain automatically by {@code addExchangeInterceptor}.
 */
public interface AsyncHttpExchangeInterceptor {

    /**
     * Intercepts one asynchronous exchange.
     *
     * @param request request to send or replace
     * @param chain next interceptor stage
     * @param <T> response body type selected by the terminal body handler
     * @return a future for the final response
     */
    <T> CompletableFuture<HttpResponse<T>> interceptAsync(
            HttpRequest request, AsyncExchangeChain<T> chain);

    /** Represents the next asynchronous stage of an exchange chain. */
    interface AsyncExchangeChain<T> {

        /**
         * Continues the chain with the supplied request.
         *
         * @param request request to pass to the next stage
         * @return a future for the downstream response
         */
        CompletableFuture<HttpResponse<T>> proceed(HttpRequest request);
    }
}
