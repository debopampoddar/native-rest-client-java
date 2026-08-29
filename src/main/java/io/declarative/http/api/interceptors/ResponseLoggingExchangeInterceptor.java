package io.declarative.http.api.interceptors;

import io.declarative.http.security.HeaderSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * Logs response status and sanitized metadata without logging response bodies.
 */
public final class ResponseLoggingExchangeInterceptor
        implements HttpExchangeInterceptor, AsyncHttpExchangeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ResponseLoggingExchangeInterceptor.class);

    @Override
    public <T> HttpResponse<T> intercept(HttpRequest request,
                                         ExchangeChain<T> chain)
            throws IOException, InterruptedException {

        HttpResponse<T> response = chain.proceed(request);

        logResponse(request, response);
        return response;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
            HttpRequest request, AsyncExchangeChain<T> chain) {
        return chain.proceed(request).thenApply(response -> {
            logResponse(request, response);
            return response;
        });
    }

    private static void logResponse(HttpRequest request, HttpResponse<?> response) {
        log.info("← {} {} status={} headers={}",
                request.method(), HeaderSanitizer.sanitize(request.uri()),
                response.statusCode(), HeaderSanitizer.sanitize(response.headers()));

    }
}
