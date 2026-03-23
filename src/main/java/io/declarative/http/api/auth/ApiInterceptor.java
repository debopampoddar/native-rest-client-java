package io.declarative.http.api.auth;

import io.declarative.http.api.interceptors.RequestExecutor;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * An interface for intercepting API requests to modify them before execution.
 *
 * @author Debopam
 */
public interface ApiInterceptor {
    /**
     * Intercepts the HTTP request, optionally modifies it, and passes it to the next
     * executor in the chain.
     *
     * @param request the current HTTP request
     * @param chain   the next request executor in the chain
     * @return a future completing with the HTTP response
     */
    CompletableFuture<HttpResponse<String>> intercept(HttpRequest request, RequestExecutor chain);
}