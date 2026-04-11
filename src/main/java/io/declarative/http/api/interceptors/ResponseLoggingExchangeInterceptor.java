package io.declarative.http.api.interceptors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Log status, headers, and maybe the first N bytes of a response body for debugging, without
 * changing existing business code.
 */
public final class ResponseLoggingExchangeInterceptor implements HttpExchangeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ResponseLoggingExchangeInterceptor.class);
    private static final int MAX_BODY_PREVIEW = 1024;

    @Override
    public <T> HttpResponse<T> intercept(HttpRequest request,
                                         ExchangeChain<T> chain)
            throws IOException, InterruptedException {

        HttpResponse<T> response = chain.proceed(request);

        log.info("← {} {} status={} headers={}",
                request.method(),
                request.uri(),
                response.statusCode(),
                response.headers().map());

        // Example: if the body type is String, log a truncated preview
        if (response.body() instanceof String s) {
            String preview = s.length() <= MAX_BODY_PREVIEW
                    ? s
                    : s.substring(0, MAX_BODY_PREVIEW) + "...";
            log.debug("Response body preview: {}", preview);
        }

        return response;
    }
}
