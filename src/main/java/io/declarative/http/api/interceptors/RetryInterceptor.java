package io.declarative.http.api.interceptors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpRequest;

public final class RetryInterceptor implements ClientInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RetryInterceptor.class);

    private final int maxAttempts;
    private final long initialDelayMs;

    public RetryInterceptor(int maxAttempts, long initialDelayMs) {
        this.maxAttempts = maxAttempts;
        this.initialDelayMs = initialDelayMs;
    }

    @Override
    public HttpRequest intercept(HttpRequest request, InterceptorChain chain) throws IOException {
        int attempt = 0;
        long delay = initialDelayMs;

        while (true) {
            try {
                return chain.proceed(request);
            } catch (IOException e) {
                attempt++;
                if (attempt >= maxAttempts) throw e;
                log.warn("Attempt {}/{} failed: {}. Retrying in {}ms...",
                        attempt, maxAttempts, e.getMessage(), delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Retry interrupted", ie);
                }
                delay = Math.min(delay * 2, 30_000L); // cap at 30s
            }
        }
    }
}
