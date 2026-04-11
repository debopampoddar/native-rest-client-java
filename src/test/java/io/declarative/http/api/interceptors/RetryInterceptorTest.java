package io.declarative.http.api.interceptors;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryInterceptorTest {

    private static HttpRequest dummyRequest() {
        return HttpRequest.newBuilder(URI.create("http://localhost/test")).GET().build();
    }

    @Test
    void successfulOnFirstAttempt_doesNotRetry() throws IOException {
        RetryInterceptor interceptor = new RetryInterceptor(3, 1L);
        AtomicInteger calls = new AtomicInteger();

        ClientInterceptor terminal = (request, chain) -> {
            calls.incrementAndGet();
            return request;
        };

        InterceptorChain chain = new InterceptorChain(List.of(terminal));
        HttpRequest result = interceptor.intercept(dummyRequest(), chain);

        assertThat(result.uri()).isEqualTo(dummyRequest().uri());
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void retriesOnIOException_untilSuccess() throws IOException {
        RetryInterceptor interceptor = new RetryInterceptor(3, 1L);
        AtomicInteger calls = new AtomicInteger();

        ClientInterceptor terminal = (request, chain) -> {
            int c = calls.incrementAndGet();
            if (c < 3) {
                throw new IOException("boom-" + c);
            }
            return request;
        };

        InterceptorChain chain = new InterceptorChain(List.of(terminal));
        HttpRequest result = interceptor.intercept(dummyRequest(), chain);

        assertThat(result).isNotNull();
        assertThat(calls.get()).isEqualTo(3); // 2 failures + 1 success
    }

    @Test
    void retriesUpToMaxAttempts_thenThrows() {
        RetryInterceptor interceptor = new RetryInterceptor(2, 1L);
        AtomicInteger calls = new AtomicInteger();

        ClientInterceptor terminal = (request, chain) -> {
            calls.incrementAndGet();
            throw new IOException("always fails");
        };

        InterceptorChain chain = new InterceptorChain(List.of(terminal));

        assertThatThrownBy(() -> interceptor.intercept(dummyRequest(), chain))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("always fails");

        assertThat(calls.get()).isEqualTo(2); // exactly maxAttempts
    }

    @Test
    void interruptedDuringSleep_wrapsAsIOExceptionAndPreservesInterruptFlag() {
        RetryInterceptor interceptor = new RetryInterceptor(3, 1L);
        AtomicInteger calls = new AtomicInteger();

        ClientInterceptor terminal = (request, chain) -> {
            calls.incrementAndGet();
            if (calls.get() == 1) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("fail-" + calls.get());
        };

        InterceptorChain chain = new InterceptorChain(List.of(terminal));

        assertThatThrownBy(() -> interceptor.intercept(dummyRequest(), chain))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Retry interrupted");

        assertThat(Thread.currentThread().isInterrupted()).isTrue();

        // Clear flag so it does not leak into other tests
        Thread.interrupted();
    }
}
