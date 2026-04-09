package io.declarative.http.api.interceptors;

import io.declarative.http.security.HeaderSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpRequest;

public final class LoggingInterceptor implements ClientInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoggingInterceptor.class);

    @Override
    public HttpRequest intercept(HttpRequest request, InterceptorChain chain) throws IOException {
        long start = System.currentTimeMillis();
        log.info("→ {} {} headers={}",
                request.method(), request.uri(),
                HeaderSanitizer.sanitize(request.headers()));

        HttpRequest next = chain.proceed(request);

        log.info("← completed in {}ms", System.currentTimeMillis() - start);
        return next;
    }
}
