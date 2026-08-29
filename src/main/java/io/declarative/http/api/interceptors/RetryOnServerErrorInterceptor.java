package io.declarative.http.api.interceptors;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Retries transient failures for safe {@code GET} and {@code HEAD} requests.
 *
 * <p>The interceptor retries transport {@link IOException}s plus HTTP 429 and
 * 5xx responses. Discarded retry responses are closed before another attempt.
 * A valid {@code Retry-After} header takes precedence over exponential backoff,
 * subject to {@link RetryPolicy#maxBackoff()}; locally computed delays receive
 * the policy's optional jitter.
 */
public final class RetryOnServerErrorInterceptor
        implements HttpExchangeInterceptor, AsyncHttpExchangeInterceptor {

    private final RetryPolicy policy;

    /**
     * Creates the legacy deterministic policy shape used by earlier releases.
     *
     * @param maxAttempts total attempts including the initial request
     * @param initialBackoffMillis first retry delay in milliseconds
     */
    public RetryOnServerErrorInterceptor(int maxAttempts, long initialBackoffMillis) {
        this(legacyPolicy(maxAttempts, initialBackoffMillis));
    }

    /**
     * Creates a retry interceptor from an immutable policy.
     *
     * @param policy non-null retry configuration
     */
    public RetryOnServerErrorInterceptor(RetryPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Executes the synchronous retry loop.
     *
     * @param request outgoing request
     * @param chain remaining exchange chain
     * @param <T> response body type
     * @return final response, which the caller owns
     * @throws IOException if all transport attempts fail
     * @throws InterruptedException if interrupted while waiting to retry
     */
    @Override
    public <T> HttpResponse<T> intercept(HttpRequest request,
                                         ExchangeChain<T> chain)
            throws IOException, InterruptedException {
        if (!isRetryableMethod(request)) {
            return chain.proceed(request);
        }

        int attempt = 1;
        long backoff = policy.initialBackoff().toMillis();
        while (true) {
            try {
                HttpResponse<T> response = chain.proceed(request);
                if (!isRetryableStatus(response.statusCode()) || attempt >= policy.maxAttempts()) {
                    return response;
                }
                long delay = retryDelay(response, backoff);
                ResponseBodies.close(response);
                sleep(delay);
                backoff = nextBackoff(backoff);
                attempt++;
            } catch (IOException e) {
                if (attempt >= policy.maxAttempts()) {
                    throw e;
                }
                sleep(jitter(backoff));
                backoff = nextBackoff(backoff);
                attempt++;
            }
        }
    }

    /**
     * Executes the asynchronous retry loop without blocking a caller thread.
     *
     * @param request outgoing request
     * @param chain remaining asynchronous exchange chain
     * @param <T> response body type
     * @return future for the final response
     */
    @Override
    public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
            HttpRequest request, AsyncExchangeChain<T> chain) {
        if (!isRetryableMethod(request)) {
            return chain.proceed(request);
        }
        return attemptAsync(request, chain, 1, policy.initialBackoff().toMillis());
    }

    /**
     * Performs one asynchronous attempt and schedules the next attempt when the
     * response or transport exception is retryable.
     *
     * @param request request to replay safely
     * @param chain exchange chain
     * @param attempt one-based current attempt count
     * @param backoff locally calculated delay for the next retry
     * @param <T> response body type
     * @return future for the eventual response or failure
     */
    private <T> CompletableFuture<HttpResponse<T>> attemptAsync(
            HttpRequest request, AsyncExchangeChain<T> chain,
            int attempt, long backoff) {
        return chain.proceed(request)
                .handle((response, failure) -> {
                    if (failure != null) {
                        Throwable cause = unwrap(failure);
                        if (!(cause instanceof IOException) || attempt >= policy.maxAttempts()) {
                            return RetryOnServerErrorInterceptor
                                    .<HttpResponse<T>>failedFuture(cause);
                        }
                        return delay(jitter(backoff)).thenCompose(ignored ->
                                attemptAsync(request, chain, attempt + 1,
                                        nextBackoff(backoff)));
                    }
                    if (isRetryableStatus(response.statusCode())
                            && attempt < policy.maxAttempts()) {
                        try {
                            long delay = retryDelay(response, backoff);
                            ResponseBodies.close(response);
                            return delay(delay).thenCompose(ignored ->
                                    attemptAsync(request, chain, attempt + 1,
                                            nextBackoff(backoff)));
                        } catch (IOException e) {
                            return RetryOnServerErrorInterceptor
                                    .<HttpResponse<T>>failedFuture(e);
                        }
                    }
                    return CompletableFuture.completedFuture(response);
                })
                .thenCompose(future -> future);
    }

    /**
     * Returns whether a request can be replayed without changing application state.
     *
     * @param request candidate request
     * @return {@code true} only for GET and HEAD
     */
    private static boolean isRetryableMethod(HttpRequest request) {
        return request.method().equals("GET") || request.method().equals("HEAD");
    }

    /**
     * Returns whether a response indicates temporary service or rate-limit failure.
     *
     * @param status HTTP status code
     * @return {@code true} for 429 and 5xx statuses
     */
    private static boolean isRetryableStatus(int status) {
        return status == 429 || (status >= 500 && status < 600);
    }

    /**
     * Chooses a server-provided delay when valid, otherwise applies local jitter.
     *
     * @param response retryable response
     * @param backoff calculated exponential delay
     * @return non-negative delay in milliseconds
     */
    private long retryDelay(HttpResponse<?> response, long backoff) {
        return retryAfterMillis(response).orElseGet(() -> jitter(backoff));
    }

    /**
     * Parses the standard {@code Retry-After} delta-seconds or RFC-1123 date form.
     *
     * @param response retryable HTTP response
     * @return capped delay when the header is valid
     */
    private java.util.Optional<Long> retryAfterMillis(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .flatMap(this::parseRetryAfter)
                .map(this::capDelay);
    }

    /**
     * Parses one raw retry-after header without treating malformed values as errors.
     *
     * @param header raw header value
     * @return non-negative delay in milliseconds when recognized
     */
    private java.util.Optional<Long> parseRetryAfter(String header) {
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds < 0
                    ? java.util.Optional.empty()
                    : java.util.Optional.of(safeMillis(seconds));
        } catch (NumberFormatException ignored) {
            try {
                Instant retryAt = ZonedDateTime.parse(header,
                        DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return java.util.Optional.of(Math.max(0L,
                        Duration.between(Instant.now(), retryAt).toMillis()));
            } catch (DateTimeParseException ignoredAgain) {
                return java.util.Optional.empty();
            }
        }
    }

    /**
     * Prevents seconds-to-milliseconds overflow for hostile server headers.
     *
     * @param seconds non-negative delay in seconds
     * @return delay in milliseconds, saturated at {@link Long#MAX_VALUE}
     */
    private static long safeMillis(long seconds) {
        return seconds > Long.MAX_VALUE / 1_000L ? Long.MAX_VALUE : seconds * 1_000L;
    }

    /**
     * Caps a delay to the policy maximum.
     *
     * @param delay proposed non-negative delay
     * @return capped delay
     */
    private long capDelay(long delay) {
        return Math.min(delay, policy.maxBackoff().toMillis());
    }

    /**
     * Applies symmetric random jitter to a locally calculated backoff delay.
     *
     * @param delay base delay in milliseconds
     * @return adjusted non-negative delay
     */
    private long jitter(long delay) {
        if (delay == 0 || policy.jitterFactor() == 0d) {
            return delay;
        }
        double multiplier = ThreadLocalRandom.current().nextDouble(
                1d - policy.jitterFactor(), 1d + policy.jitterFactor());
        return Math.max(0L, Math.round(delay * multiplier));
    }

    /**
     * Doubles a locally calculated backoff up to the configured maximum.
     *
     * @param backoff current delay
     * @return next capped delay
     */
    private long nextBackoff(long backoff) {
        long cap = policy.maxBackoff().toMillis();
        if (backoff >= cap || backoff > Long.MAX_VALUE / 2) {
            return cap;
        }
        return Math.min(cap, backoff * 2);
    }

    /**
     * Blocks the synchronous path for one retry delay.
     *
     * @param millis non-negative delay
     * @throws InterruptedException if the caller interrupts the wait
     */
    private static void sleep(long millis) throws InterruptedException {
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }

    /**
     * Schedules an asynchronous delay without consuming a worker thread.
     *
     * @param millis non-negative delay
     * @return completion future after the delay
     */
    private static CompletableFuture<Void> delay(long millis) {
        return CompletableFuture.runAsync(
                () -> { }, CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS));
    }

    /**
     * Removes the wrapper introduced by asynchronous completion stages.
     *
     * @param failure completion failure
     * @return underlying cause when present
     */
    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
    }

    /**
     * Creates a failed generic future on Java versions whose overload inference
     * would otherwise obscure the response type.
     *
     * @param failure terminal failure
     * @param <T> future value type
     * @return failed future
     */
    private static <T> CompletableFuture<T> failedFuture(Throwable failure) {
        return CompletableFuture.failedFuture(failure);
    }

    /**
     * Retains the original constructor's validation and no-jitter behavior.
     *
     * @param maxAttempts total request attempts
     * @param initialBackoffMillis first retry delay
     * @return equivalent immutable policy
     */
    private static RetryPolicy legacyPolicy(int maxAttempts, long initialBackoffMillis) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (initialBackoffMillis < 0) {
            throw new IllegalArgumentException("initialBackoffMillis must not be negative");
        }
        long maxBackoff = Math.max(initialBackoffMillis, 30_000L);
        return RetryPolicy.builder()
                .maxAttempts(maxAttempts)
                .initialBackoff(Duration.ofMillis(initialBackoffMillis))
                .maxBackoff(Duration.ofMillis(maxBackoff))
                .jitterFactor(0d)
                .build();
    }
}
