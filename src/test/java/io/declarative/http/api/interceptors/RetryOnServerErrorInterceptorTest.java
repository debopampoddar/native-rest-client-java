package io.declarative.http.api.interceptors;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.declarative.http.api.annotation.GET;
import io.declarative.http.client.NativeRestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryOnServerErrorInterceptorTest {

    private static WireMockServer wm;

    interface RetryApi {
        @GET("/limited")
        String limited();
    }

    @BeforeAll
    static void start() {
        wm = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wm.start();
    }

    @AfterAll
    static void stop() {
        if (wm != null) {
            wm.stop();
        }
    }

    @BeforeEach
    void reset() {
        wm.resetAll();
    }

    @Test
    @DisplayName("Retry policy retries 429 and honors a zero Retry-After response")
    void retryPolicy_retries429AndHonorsZeroRetryAfter() {
        wm.stubFor(get("/limited")
                .inScenario("rate-limited")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
                .willSetStateTo("recovered"));
        wm.stubFor(get("/limited")
                .inScenario("rate-limited")
                .whenScenarioStateIs("recovered")
                .willReturn(ok("recovered")));

        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(2)
                .initialBackoff(Duration.ofSeconds(5))
                .maxBackoff(Duration.ofSeconds(5))
                .jitterFactor(0d)
                .build();
        try (NativeRestClient client = NativeRestClient.builder(baseUrl())
                .addExchangeInterceptor(new RetryOnServerErrorInterceptor(policy))
                .build()) {
            assertThat(client.create(RetryApi.class).limited()).isEqualTo("recovered");
        }

        wm.verify(2, getRequestedFor(urlEqualTo("/limited")));
    }

    @Test
    @DisplayName("Retry policy rejects an invalid maximum backoff configuration")
    void retryPolicy_rejectsInvalidMaximumBackoffConfiguration() {
        assertThatThrownBy(() -> RetryPolicy.builder()
                .initialBackoff(Duration.ofSeconds(2))
                .maxBackoff(Duration.ofSeconds(1))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxBackoff");
        assertThatThrownBy(() -> RetryPolicy.builder().jitterFactor(1.1d))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jitterFactor");
    }

    /**
     * Builds the local WireMock root URL used by retry tests.
     *
     * @return HTTP base URL for the current test server
     */
    private static String baseUrl() {
        return "http://localhost:" + wm.port();
    }
}
