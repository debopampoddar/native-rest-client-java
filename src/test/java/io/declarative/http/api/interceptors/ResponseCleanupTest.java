package io.declarative.http.api.interceptors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseCleanupTest {

    private static final HttpRequest REQUEST = HttpRequest.newBuilder(
                    URI.create("https://example.test/data"))
            .header("Authorization", "Bearer old")
            .build();

    @Test
    @DisplayName("Retry closes discarded response before retry")
    void retry_closesDiscardedResponseBeforeRetry() throws Exception {
        TrackingInputStream discarded = new TrackingInputStream();
        AtomicInteger calls = new AtomicInteger();
        RetryOnServerErrorInterceptor interceptor =
                new RetryOnServerErrorInterceptor(2, 0);

        HttpResponse<InputStream> result = interceptor.intercept(REQUEST, request ->
                calls.getAndIncrement() == 0
                        ? response(503, discarded, Map.of())
                        : response(200, new ByteArrayInputStream(new byte[0]), Map.of()));

        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(discarded.closed).isTrue();
    }

    @Test
    @DisplayName("Token refresh closes rejected response before replay")
    void tokenRefresh_closesRejectedResponseBeforeReplay() throws Exception {
        TrackingInputStream rejected = new TrackingInputStream();
        AtomicInteger calls = new AtomicInteger();
        TokenRefreshExchangeInterceptor interceptor =
                new TokenRefreshExchangeInterceptor(() -> "fresh", () -> { });

        HttpResponse<InputStream> result = interceptor.intercept(REQUEST, request ->
                calls.getAndIncrement() == 0
                        ? response(401, rejected,
                        Map.of("WWW-Authenticate", List.of("Bearer realm=\"api\"")))
                        : response(200, new ByteArrayInputStream(new byte[0]), Map.of()));

        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(rejected.closed).isTrue();
    }

    private static HttpResponse<InputStream> response(
            int status, InputStream body, Map<String, List<String>> headers) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return status; }
            @Override public HttpRequest request() { return REQUEST; }
            @Override public Optional<HttpResponse<InputStream>> previousResponse() {
                return Optional.empty();
            }
            @Override public HttpHeaders headers() {
                return HttpHeaders.of(headers, (name, value) -> true);
            }
            @Override public InputStream body() { return body; }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return REQUEST.uri(); }
            @Override public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInputStream() {
            super(new byte[0]);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
