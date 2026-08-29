package io.declarative.http.api.interceptors;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.declarative.http.api.annotation.Body;
import io.declarative.http.api.annotation.GET;
import io.declarative.http.api.annotation.POST;
import io.declarative.http.api.annotation.Path;
import io.declarative.http.api.auth.BearerAuthInterceptor;
import io.declarative.http.api.auth.oauth.AccessToken;
import io.declarative.http.api.auth.oauth.OAuth2Decorator;
import io.declarative.http.api.auth.oauth.RefreshingTokenManager;
import io.declarative.http.api.util.metrics.MetricsRecorder;
import io.declarative.http.client.HttpResponseEnvelope;
import io.declarative.http.client.NativeRestClient;
import io.declarative.http.error.ApiException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

        @GET("/data")
        CompletableFuture<String> getDataAsync();
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
    @DisplayName("Metrics records successful call")
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
    @DisplayName("Metrics records successful async call")
    void metrics_recordsSuccessfulAsyncCall() {
        wm.stubFor(get("/data").willReturn(ok("response")));
        var recorder = new TestMetricsRecorder();
        NativeRestClient c = clientWith(new MetricsExchangeInterceptor(recorder));

        assertThat(c.create(SimpleApi.class).getDataAsync().join())
                .isEqualTo("response");
        assertThat(recorder.calls).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("GET");
            assertThat(call.status()).isEqualTo(200);
            assertThat(call.error()).isFalse();
        });
    }

    @Test
    @DisplayName("Metrics records call even on4xx")
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
    @DisplayName("Metrics records call even on5xx")
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
    @DisplayName("Retry first attempt503 second attempt succeeds")
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
    @DisplayName("Retry does not retry post")
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
    @DisplayName("Retry exhausts max attempts throws api exception")
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
    @DisplayName("Retry passes through immediately on200")
    void retry_passesThroughImmediatelyOn200() {
        wm.stubFor(get("/data").willReturn(ok("fine")));
        NativeRestClient c = NativeRestClient.builder("http://localhost:" + wm.port())
                .addExchangeInterceptor(new RetryOnServerErrorInterceptor(3, 1L))
                .build();
        assertThat(c.create(SimpleApi.class).getData()).isEqualTo("fine");
        wm.verify(1, getRequestedFor(urlEqualTo("/data")));
    }

    @Test
    @DisplayName("Retry passes through on4xx no retry")
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

    @Test
    @DisplayName("Retry rejects invalid configuration")
    void retry_rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new RetryOnServerErrorInterceptor(0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");
        assertThatThrownBy(() -> new RetryOnServerErrorInterceptor(1, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initialBackoffMillis");
    }

    @Test
    @DisplayName("Async retry first attempt503 second attempt succeeds")
    void asyncRetry_firstAttempt503_secondAttemptSucceeds() {
        wm.stubFor(get("/data")
                .inScenario("async-retry")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("retried"));
        wm.stubFor(get("/data")
                .inScenario("async-retry")
                .whenScenarioStateIs("retried")
                .willReturn(ok("recovered")));

        try (NativeRestClient c = NativeRestClient.builder(
                "http://localhost:" + wm.port())
                .addExchangeInterceptor(new RetryOnServerErrorInterceptor(3, 1L))
                .build()) {
            assertThat(c.create(SimpleApi.class).getDataAsync().join())
                    .isEqualTo("recovered");
        }
        wm.verify(2, getRequestedFor(urlEqualTo("/data")));
    }

    // ── TokenRefreshExchangeInterceptor ───────────────────────────────────────

    @Test
    @DisplayName("Token refresh on401 refreshes and retries")
    void tokenRefresh_on401_refreshesAndRetries() {
        AtomicReference<String> currentToken = new AtomicReference<>("old-token");
        AtomicBoolean refreshCalled = new AtomicBoolean(false);

        wm.stubFor(get("/data")
                .withHeader("Authorization", equalTo("Bearer old-token"))
                .willReturn(aResponse().withStatus(401)
                        .withHeader("WWW-Authenticate", "Bearer realm=\"api\"")));
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
    @DisplayName("Token refresh skips refresh on success")
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
    @DisplayName("Token refresh still returns envelope on401 when already expired")
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

    @Test
    @DisplayName("Token refresh does not refresh on403")
    void tokenRefresh_doesNotRefreshOn403() {
        AtomicBoolean refreshCalled = new AtomicBoolean();
        wm.stubFor(get("/data").willReturn(aResponse().withStatus(403)));
        NativeRestClient c = NativeRestClient.builder("http://localhost:" + wm.port())
                .addExchangeInterceptor(new TokenRefreshExchangeInterceptor(
                        () -> "new-token", () -> refreshCalled.set(true)))
                .build();

        assertThatThrownBy(() -> c.create(SimpleApi.class).getData())
                .isInstanceOf(ApiException.class);
        assertThat(refreshCalled).isFalse();
        wm.verify(1, getRequestedFor(urlEqualTo("/data")));
    }

    @Test
    @DisplayName("Token refresh does not refresh without bearer challenge")
    void tokenRefresh_doesNotRefreshWithoutBearerChallenge() {
        AtomicBoolean refreshCalled = new AtomicBoolean();
        wm.stubFor(get("/data").willReturn(aResponse().withStatus(401)));
        NativeRestClient c = NativeRestClient.builder("http://localhost:" + wm.port())
                .addExchangeInterceptor(new TokenRefreshExchangeInterceptor(
                        () -> "new-token", () -> refreshCalled.set(true)))
                .build();

        assertThatThrownBy(() -> c.create(SimpleApi.class).getData())
                .isInstanceOf(ApiException.class);
        assertThat(refreshCalled).isFalse();
        wm.verify(1, getRequestedFor(urlEqualTo("/data")));
    }

    @Test
    @DisplayName("Token refresh does not replay same token")
    void tokenRefresh_doesNotReplaySameToken() {
        AtomicInteger refreshes = new AtomicInteger();
        wm.stubFor(get("/data").willReturn(aResponse().withStatus(401)
                .withHeader("WWW-Authenticate", "Bearer realm=\"api\"")));
        NativeRestClient c = NativeRestClient.builder("http://localhost:" + wm.port())
                .addInterceptor(new BearerAuthInterceptor("same-token"))
                .addExchangeInterceptor(new TokenRefreshExchangeInterceptor(
                        () -> "same-token", refreshes::incrementAndGet))
                .build();

        assertThatThrownBy(() -> c.create(SimpleApi.class).getData())
                .isInstanceOf(ApiException.class);
        assertThat(refreshes).hasValue(1);
        wm.verify(1, getRequestedFor(urlEqualTo("/data")));
    }

    @Test
    @DisplayName("Oauth decorator async concurrent401s perform one refresh")
    void oauthDecorator_asyncConcurrent401sPerformOneRefresh() {
        AtomicInteger fetches = new AtomicInteger();
        RefreshingTokenManager tokens = new RefreshingTokenManager(
                () -> new AccessToken(
                        fetches.incrementAndGet() == 1 ? "old-token" : "fresh-token",
                        Instant.now().plusSeconds(300)),
                Duration.ofSeconds(30), null);
        wm.stubFor(get("/data")
                .withHeader("Authorization", equalTo("Bearer old-token"))
                .willReturn(aResponse().withStatus(401)
                        .withHeader("WWW-Authenticate", "Bearer realm=\"api\"")));
        wm.stubFor(get("/data")
                .withHeader("Authorization", equalTo("Bearer fresh-token"))
                .willReturn(ok("authenticated")));

        try (tokens;
             NativeRestClient c = OAuth2Decorator.with(tokens)
                     .applyTo(NativeRestClient.builder(
                             "http://localhost:" + wm.port()))
                     .build()) {
            SimpleApi api = c.create(SimpleApi.class);
            List<CompletableFuture<String>> calls = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                calls.add(api.getDataAsync());
            }
            CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).join();
            assertThat(calls).allSatisfy(call ->
                    assertThat(call.join()).isEqualTo("authenticated"));
        }

        assertThat(fetches).hasValue(2);
    }

    // ── ResponseLoggingExchangeInterceptor ────────────────────────────────────

    @Test
    @DisplayName("Response logging passes response unchanged")
    void responseLogging_passesResponseUnchanged() {
        wm.stubFor(get("/data").willReturn(ok("response-body").withHeader("X-Custom", "value")));
        NativeRestClient c = clientWith(new ResponseLoggingExchangeInterceptor());
        assertThat(c.create(SimpleApi.class).getData()).isEqualTo("response-body");
    }

    @Test
    @DisplayName("Response logging does not suppress api exception")
    void responseLogging_doesNotSuppressApiException() {
        wm.stubFor(get("/data").willReturn(aResponse().withStatus(500).withBody("error")));
        NativeRestClient c = clientWith(new ResponseLoggingExchangeInterceptor());
        assertThatThrownBy(() -> c.create(SimpleApi.class).getData())
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatusCode()).isEqualTo(500));
    }

    @Test
    @DisplayName("Response logging with envelope preserves status")
    void responseLogging_withEnvelopePreservesStatus() {
        wm.stubFor(get("/data").willReturn(aResponse().withStatus(404).withBody("not-found")));
        NativeRestClient c = clientWith(new ResponseLoggingExchangeInterceptor());
        HttpResponseEnvelope<String> env = c.create(SimpleApi.class).getDataEnvelope();
        assertThat(env.status()).isEqualTo(404);
        assertThat(env.isSuccessful()).isFalse();
    }

    // ── Exchange interceptor ordering ─────────────────────────────────────────

    @Test
    @DisplayName("Multiple interceptors executed in registration order")
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
    @DisplayName("Metrics and retry combined metrics sees total duration")
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
