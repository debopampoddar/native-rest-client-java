package io.declarative.http.api.interceptors;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Retry transient failures (5xx, timeouts) with backoff, but only for idempotent methods (e.g., GET, HEAD).
 */

public final class RetryOnServerErrorInterceptor implements HttpExchangeInterceptor {

    private final int maxAttempts;
    private final long initialBackoffMillis;

    public RetryOnServerErrorInterceptor(int maxAttempts, long initialBackoffMillis) {
        this.maxAttempts = maxAttempts;
        this.initialBackoffMillis = initialBackoffMillis;
    }

    @Override
    public <T> HttpResponse<T> intercept(HttpRequest request,
                                         ExchangeChain<T> chain)
            throws IOException, InterruptedException {

        // Only retry safe/idempotent methods
        String method = request.method();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            return chain.proceed(request);
        }

        int attempt = 0;
        long backoff = initialBackoffMillis;

        while (true) {
            try {
                HttpResponse<T> response = chain.proceed(request);
                int status = response.statusCode();
                if (status >= 500 && status < 600 && attempt < maxAttempts - 1) {
                    Thread.sleep(backoff);
                    backoff = Math.min(backoff * 2, 30_000L);
                    attempt++;
                    continue; // retry
                }
                return response; // success or non-retriable status
            } catch (IOException e) {
                attempt++;
                if (attempt >= maxAttempts) {
                    throw e;
                }
                Thread.sleep(backoff);
                backoff = Math.min(backoff * 2, 30_000L);
            }
        }
    }
}
