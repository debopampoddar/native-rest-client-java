package io.declarative.http.api.interceptors;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * An executor responsible for forwarding the HTTP request along the execution chain.
 *
 * @author Debopam
 */
public interface RequestExecutor {
    /**
     * Executes the given HTTP request asynchronously.
     *
     * @param request the current HTTP request to execute
     * @return a future completing with the HTTP response
     */
    CompletableFuture<HttpResponse<String>> execute(HttpRequest request);
}