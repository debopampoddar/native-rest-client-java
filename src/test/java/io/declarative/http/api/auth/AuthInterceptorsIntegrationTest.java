package io.declarative.http.api.auth;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.declarative.http.api.auth.oauth.OAuthInterceptor;
import io.declarative.http.api.interceptors.LoggingInterceptor;
import io.declarative.http.client.NativeRestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for BasicAuthInterceptor and OAuthInterceptor.
 * These tests verify that:
 *  - NativeRestClient applies interceptors to real HTTP calls.
 *  - WireMock receives correct Authorization headers on the server side,
 *    matching Basic and Bearer schemes [web:82][web:85][web:93][web:140].
 */
class AuthInterceptorsIntegrationTest {

    private static WireMockServer wireMock;
    private AuthIntegrationApi api;
    private FakeTokenManager tokenManager;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void setUpClient() {
        wireMock.resetAll();

        // --- Basic auth credentials for tests ---
        String username = "user";
        String password = "secret";

        // --- OAuth token manager for tests ---
        tokenManager = new FakeTokenManager("initial-access-token");

        NativeRestClient client = NativeRestClient
                .builder("http://localhost:" + wireMock.port())
                .addInterceptor(new LoggingInterceptor())
                .addInterceptor(new BasicAuthInterceptor(() -> username, () -> password))
                .addInterceptor(new OAuthInterceptor(tokenManager)) // Bearer
                .build();

        api = client.create(AuthIntegrationApi.class);
    }

    @Test
    void basicAuthInterceptor_sendsCorrectAuthorizationHeader() {
        // Per RFC 7617: Authorization: Basic base64(username:password) [web:85][web:87][web:139]
        String userPass = "user:secret";
        String encoded = Base64.getEncoder()
                .encodeToString(userPass.getBytes(StandardCharsets.UTF_8));
        String expectedBasicHeader = "Basic " + encoded;

        // We expect BOTH Basic and Bearer headers to be present because both interceptors run.
        // To make the assertion clear, we match on the Basic portion here.
        wireMock.stubFor(get(urlEqualTo("/basic-protected"))
                .withHeader("Authorization", containing("Basic "))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("OK-BASIC")));

        String response = api.basicProtected();

        assertThat(response).isEqualTo("OK-BASIC");

        wireMock.verify(getRequestedFor(urlEqualTo("/basic-protected"))
                .withHeader("Authorization", containing(expectedBasicHeader)));
    }

    @Test
    void oauthInterceptor_sendsBearerTokenFromTokenManager() {
        // Bearer scheme per RFC 6750 / OAuth 2.0 Simplified [web:80][web:82][web:136][web:140]
        String expectedBearer = "Bearer initial-access-token";

        wireMock.stubFor(get(urlEqualTo("/oauth-protected"))
                .withHeader("Authorization", equalTo(expectedBearer))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("OK-OAUTH")));

        String response = api.oauthProtected();

        assertThat(response).isEqualTo("OK-OAUTH");

        wireMock.verify(getRequestedFor(urlEqualTo("/oauth-protected"))
                .withHeader("Authorization", equalTo(expectedBearer)));
    }

    @Test
    void oauthInterceptor_usesRefreshedTokenWhenTokenManagerChanges() {
        // First call with initial token
        wireMock.stubFor(get(urlEqualTo("/oauth-refreshed"))
                .inScenario("token-refresh")
                .whenScenarioStateIs(STARTED)
                .withHeader("Authorization", equalTo("Bearer initial-access-token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("OK-FIRST"))
                .willSetStateTo("REFRESHED"));

        // Second call should see updated token from TokenManager
        wireMock.stubFor(get(urlEqualTo("/oauth-refreshed"))
                .inScenario("token-refresh")
                .whenScenarioStateIs("REFRESHED")
                .withHeader("Authorization", equalTo("Bearer refreshed-access-token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("OK-SECOND")));

        // First request uses initial token
        String first = api.oauthRefreshed();
        assertThat(first).isEqualTo("OK-FIRST");

        // Simulate token refresh at application layer
        tokenManager.setToken("refreshed-access-token");

        String second = api.oauthRefreshed();
        assertThat(second).isEqualTo("OK-SECOND");

        wireMock.verify(1, getRequestedFor(urlEqualTo("/oauth-refreshed"))
                .withHeader("Authorization", equalTo("Bearer initial-access-token")));
        wireMock.verify(1, getRequestedFor(urlEqualTo("/oauth-refreshed"))
                .withHeader("Authorization", equalTo("Bearer refreshed-access-token")));
    }
}
