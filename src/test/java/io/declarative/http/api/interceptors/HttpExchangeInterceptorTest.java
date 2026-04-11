package io.declarative.http.api.interceptors;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.declarative.http.api.annotation.Body;
import io.declarative.http.api.annotation.GET;
import io.declarative.http.api.annotation.POST;
import io.declarative.http.api.annotation.Path;
import io.declarative.http.api.auth.BearerAuthInterceptor;
import io.declarative.http.api.util.metrics.MetricsRecorder;
import io.declarative.http.client.HttpResponseEnvelope;
import io.declarative.http.client.NativeRestClient;
import io.declarative.http.error.ApiException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpExchangeInterceptorTest {

    private static WireMockServer wm;

    interface SimpleApi {
        @GET("/data")
        String getData();

        @GET("/data")
        HttpResponseEnvelope<String> getDataEnvelope();

        @GET("/users/{id}")
        String getUser(@Path("id") long id);

        @POST("/data")
        String postData(@Body String body);
    }

    @BeforeAll
    static void start() {
        wm = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wm.start();
    }

    @AfterAll
    static void stop() { if (wm != null) wm.stop(); }

    @BeforeEach
    void reset() { wm.resetAll(); }

    // ── MetricsExchangeInterceptor ────────────────────────────────────────────

    @Test
    void metrics_recordsSuccessfulCall() {
        wm.stubFor(get("/data").willReturn(ok("response")));
        var recorder = new TestMetricsRecorder();
        NativeRestClient c = clientWith(new MetricsExchangeInterceptor(recorder));
        c.create(SimpleApi.class).getData();

        assertThat(recorder.calls).hasSize(1);
        assertThat(recorder.calls.get(0).method()).isEqualTo("GET");
        assertThat(recorder.calls.get(0).status()).isEqualTo(200);
        assertThat(recorder.calls.get(0).error()).isFalse();
        assertThat(recorder.calls.get(0).durationNanos()).isPositive();
    }

    @Test
    void metrics_recordsCallEvenOn4xx() {
        wm.stubFor(get("/data").willReturn(notFound()));
        var recorder = new TestMetricsRecorder();
        NativeRestClient c = clientWith(new MetricsExchangeInterceptor(recorder));
        assertThatThrownBy(() -> c.create(SimpleApi.class).getData())
                .isInstanceOf(ApiException.class);
        assertThat(recorder.calls).hasSize(1);
        assertThat(recorder.calls.get(0).status()).isEqualTo(404);
        assertThat(recorder.calls.get(0).error()).isFalse(); // 404 is not an IO error
    }

    @Test
    void metrics_recordsCallEvenOn5xx() {
        wm.stubFor(get("/data").willReturn(aResponse().withStatus(500)));
        var recorder = new TestMetricsRecorder();
        NativeRestClient c = clientWith(new MetricsExchangeInterceptor(recorder));
        assertThatThrownBy(() -> c.create(SimpleApi.class).getData())
                .isInstanceOf(ApiException.class);
        assertThat(recorder.calls).hasSize(1);
        assertThat(recorder.calls.get(0).status()).isEqualTo(500);
    }

    // ── RetryOnServerErrorInterceptor ─────────────────────────────────────────

    @Test
    void retry_firstAttempt503_secondAttemptSucceeds() {
        wm.stubFor(get("/data")
                .inScenario("retry")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("retried"));
        wm.stubFor(get("/data")
                .inScenario("retry")
                .whenScenarioStateIs("retried")
                .willReturn(ok("recovered")));

        NativeRestClient c = NativeRestClient.builder("http://localhost:" + wm.port())
                .addExchangeInterceptor(new RetryOnServerErrorInterceptor(3, 1L))
                .build();
        assertThat(c.create(SimpleApi.class).getData()).isEqualTo("recovered");
        wm.verify(2, getRequestedFor(urlEqualTo("/data")));
    }

    @Test
    void retry_doesNotRetryPost() {
        wm.stubFor(post("/data").willReturn(aResponse().withStatus(503).withBody("unavailable")));
        NativeRestClient c = NativeRestClient.builder("http://localhost:" + wm.port())
                .addExchangeInterceptor(new RetryOnServerErrorInterceptor(3, 1L))
                .build();
        assertThatThrownBy(() -> c.create(SimpleApi.class).postData("{\"x\":1}"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatusCode()).isEqualTo(503));
        wm.verify(1, postRequestedFor(urlEqualTo("/data"))); // exactly 1 attempt — no retry
    }

    @Test
    void retry_exhaustsMaxAttempts_throwsApiException() {
        wm.stubFor(get("/data").willReturn(aResponse().withStatus(503).withBody("down")));
        NativeRestClient c = NativeRestClient.builder("http://localhost:" + wm.port())
                .addExchangeInterceptor(new RetryOnServerErrorInterceptor(2, 1L))
                .build();
        assertThatThrownBy(() -> c.create(SimpleApi.class).getData())
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatusCode()).isEqualTo(503));
        wm.verify(2, getRequestedFor(urlEqualTo("/data"))); // exactly maxAttempts calls
    }

    @Test
    void retry_passesThroughImmediatelyOn200() {
        wm.stubFor(get("/data").willReturn(ok("fine")));
        NativeRestClient c = NativeRestClient.builder("http://localhost:" + wm.port())
                .addExchangeInterceptor(new RetryOnServerErrorInterceptor(3, 1L))
                .build();
        assertThat(c.create(SimpleApi.class).getData()).isEqualTo("fine");
        wm.verify(1, getRequestedFor(urlEqualTo("/data")));
    }

    @Test
    void retry_passesThroughOn4xx_noRetry() {
        wm.stubFor(get("/data").willReturn(aResponse().withStatus(400).withBody("bad request")));
        NativeRestClient c = NativeRestClient.builder("http://localhost:" + wm.port())
                .addExchangeInterceptor(new RetryOnServerErrorInterceptor(3, 1L))
                .build();
        assertThatThrownBy(() -> c.create(SimpleApi.class).getData())
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatusCode()).isEqualTo(400));
        wm.verify(1, getRequestedFor(urlEqualTo("/data"))); // 4xx is not retried
    }

    // ── TokenRefreshExchangeInterceptor ───────────────────────────────────────

    @Test
    void tokenRefresh_on401_refreshesAndRetries() {
        AtomicReference<String> currentToken = new AtomicReference<>("old-token");
        AtomicBoolean refreshCalled = new AtomicBoolean(false);

        wm.stubFor(get("/data")
                .withHeader("Authorization", equalTo("Bearer old-token"))
                .willReturn(aResponse().withStatus(401)));
        wm.stubFor(get("/data")
                .withHeader("Authorization", equalTo("Bearer new-token"))
                .willReturn(ok("authenticated")));

        NativeRestClient c = NativeRestClient.builder("http://localhost:" + wm.port())
                .addInterceptor(new BearerAuthInterceptor(currentToken::get))
                .addExchangeInterceptor(new TokenRefreshExchangeInterceptor(
                        currentToken::get,
                        () -> { currentToken.set("new-token"); refreshCalled.set(true); }))
                .build();

        assertThat(c.create(SimpleApi.class).getData()).isEqualTo("authenticated");
        assertThat(refreshCalled).isTrue();
    }

    @Test
    void tokenRefresh_skipsRefreshOnSuccess() {
        AtomicBoolean refreshCalled = new AtomicBoolean(false);
        wm.stubFor(get("/data").willReturn(ok("ok")));

        NativeRestClient c = NativeRestClient.builder("http://localhost:" + wm.port())
                .addExchangeInterceptor(new TokenRefreshExchangeInterceptor(
                        () -> "token",
                        () -> refreshCalled.set(true)))
                .build();

        c.create(SimpleApi.class).getData();
        assertThat(refreshCalled).isFalse();
    }

    @Test
    void tokenRefresh_stillReturnsEnvelopeOn401WhenAlreadyExpired() {
        // After refresh + retry, if still 401, the envelope reflects that
        wm.stubFor(get("/data").willReturn(aResponse().withStatus(401)));

        NativeRestClient c = NativeRestClient.builder("http://localhost:" + wm.port())
                .addExchangeInterceptor(new TokenRefreshExchangeInterceptor(
                        () -> "bad-token",
                        () -> { /* no-op: token stays bad */ }))
                .build();

        HttpResponseEnvelope<String> env = c.create(SimpleApi.class).getDataEnvelope();
        assertThat(env.status()).isEqualTo(401);
        assertThat(env.isSuccessful()).isFalse();
    }

    // ── ResponseLoggingExchangeInterceptor ────────────────────────────────────

    @Test
    void responseLogging_passesResponseUnchanged() {
        wm.stubFor(get("/data").willReturn(ok("response-body").withHeader("X-Custom", "value")));
        NativeRestClient c = clientWith(new ResponseLoggingExchangeInterceptor());
        assertThat(c.create(SimpleApi.class).getData()).isEqualTo("response-body");
    }

    @Test
    void responseLogging_doesNotSuppressApiException() {
        wm.stubFor(get("/data").willReturn(aResponse().withStatus(500).withBody("error")));
        NativeRestClient c = clientWith(new ResponseLoggingExchangeInterceptor());
        assertThatThrownBy(() -> c.create(SimpleApi.class).getData())
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatusCode()).isEqualTo(500));
    }

    @Test
    void responseLogging_withEnvelopePreservesStatus() {
        wm.stubFor(get("/data").willReturn(aResponse().withStatus(404).withBody("not-found")));
        NativeRestClient c = clientWith(new ResponseLoggingExchangeInterceptor());
        HttpResponseEnvelope<String> env = c.create(SimpleApi.class).getDataEnvelope();
        assertThat(env.status()).isEqualTo(404);
        assertThat(env.isSuccessful()).isFalse();
    }

    // ── Exchange interceptor ordering ─────────────────────────────────────────

    @Test
    void multipleInterceptors_executedInRegistrationOrder() {
        wm.stubFor(get("/data").willReturn(ok("ok")));
        List<String> order = new ArrayList<>();

        NativeRestClient c = NativeRestClient.builder("http://localhost:" + wm.port())
                .addExchangeInterceptor(new HttpExchangeInterceptor() {
                    @Override
                    public <T> HttpResponse<T> intercept(HttpRequest request, ExchangeChain<T> chain) throws IOException, InterruptedException {
                        order.add("first");
                        return chain.proceed(request);
                    }
                })
                .addExchangeInterceptor(new HttpExchangeInterceptor() {
                    @Override
                    public <T> HttpResponse<T> intercept(HttpRequest request, ExchangeChain<T> chain) throws IOException, InterruptedException {
                        order.add("second");
                        return chain.proceed(request);
                    }
                })
                .addExchangeInterceptor(new HttpExchangeInterceptor() {
                    @Override
                    public <T> HttpResponse<T> intercept(HttpRequest request, ExchangeChain<T> chain) throws IOException, InterruptedException {
                        order.add("third");
                        return chain.proceed(request);
                    }
                })
                .build();

        c.create(SimpleApi.class).getData();
        assertThat(order).containsExactly("first", "second", "third");
    }

    @Test
    void metricsAndRetry_combined_metricsSeesTotalDuration() {
        // 503 on first call, 200 on second — metrics should record the TOTAL (2 HTTP calls)
        var recorder = new TestMetricsRecorder();
        wm.stubFor(get("/data")
                .inScenario("combo")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        wm.stubFor(get("/data")
                .inScenario("combo")
                .whenScenarioStateIs("recovered")
                .willReturn(ok("success")));

        NativeRestClient c = NativeRestClient.builder("http://localhost:" + wm.port())
                .addExchangeInterceptor(new MetricsExchangeInterceptor(recorder)) // outermost
                .addExchangeInterceptor(new RetryOnServerErrorInterceptor(3, 1L)) // inner
                .build();

        assertThat(c.create(SimpleApi.class).getData()).isEqualTo("success");
        // Metrics interceptor is outermost → sees final 200 and total elapsed duration
        assertThat(recorder.calls).hasSize(1);
        assertThat(recorder.calls.get(0).status()).isEqualTo(200);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private NativeRestClient clientWith(HttpExchangeInterceptor interceptor) {
        return NativeRestClient.builder("http://localhost:" + wm.port())
                .addExchangeInterceptor(interceptor)
                .build();
    }

    record CallRecord(String method, URI uri, int status, long durationNanos, boolean error) {}

    static class TestMetricsRecorder implements MetricsRecorder {
        final List<CallRecord> calls = new ArrayList<>();

        @Override
        public void recordHttpCall(String method, URI uri, int status, long durationNanos, boolean error) {
            calls.add(new CallRecord(method, uri, status, durationNanos, error));
        }
    }
}
