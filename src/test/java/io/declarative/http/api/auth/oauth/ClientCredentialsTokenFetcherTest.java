package io.declarative.http.api.auth.oauth;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientCredentialsTokenFetcherTest {

    private static WireMockServer wm;

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
    @DisplayName("Client credentials fetcher uses Basic auth and form-encodes scope and audience")
    void clientCredentialsFetcher_usesBasicAuthAndFormEncodesScopeAndAudience() {
        wm.stubFor(post("/oauth/token")
                .withHeader("Authorization", equalTo("Basic Y2xpZW50OnNlY3JldA=="))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("grant_type=client_credentials"))
                .withRequestBody(containing("scope=read+write"))
                .withRequestBody(containing("audience=https%3A%2F%2Fapi.example.test"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"token-value\",\"expires_in\":60}")));

        Instant before = Instant.now();
        AccessToken token = ClientCredentialsTokenFetcher.builder(
                        URI.create(baseUrl() + "/oauth/token"), "client", "secret")
                .scope("read")
                .scope("write")
                .audience("https://api.example.test")
                .build()
                .fetchNewToken();

        assertThat(token.value()).isEqualTo("token-value");
        assertThat(token.expiresAt()).isAfter(before.plusSeconds(59));
        wm.verify(postRequestedFor(urlEqualTo("/oauth/token")));
    }

    @Test
    @DisplayName("Client credentials fetcher rejects malformed token responses without exposing their body")
    void clientCredentialsFetcher_rejectsMalformedTokenResponsesWithoutExposingTheirBody() {
        wm.stubFor(post("/oauth/token").willReturn(aResponse().withStatus(200)
                .withBody("{\"access_token\":\"token-value\"}")));

        assertThatThrownBy(() -> ClientCredentialsTokenFetcher.builder(
                        URI.create(baseUrl() + "/oauth/token"), "client", "secret")
                .build()
                .fetchNewToken())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expires_in")
                .satisfies(error -> assertThat(error.getMessage())
                        .doesNotContain("token-value"));
    }

    @Test
    @DisplayName("Client credentials fetcher validates endpoint and timeout configuration")
    void clientCredentialsFetcher_validatesEndpointAndTimeoutConfiguration() {
        assertThatThrownBy(() -> ClientCredentialsTokenFetcher.builder(
                        URI.create("ftp://example.test/token"), "client", "secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP(S)");
        assertThatThrownBy(() -> ClientCredentialsTokenFetcher.builder(
                        URI.create(baseUrl() + "/oauth/token"), "client", "secret")
                .requestTimeout(java.time.Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    /**
     * Builds the local WireMock root URL used by token endpoint tests.
     *
     * @return HTTP base URL for the current test server
     */
    private static String baseUrl() {
        return "http://localhost:" + wm.port();
    }
}
