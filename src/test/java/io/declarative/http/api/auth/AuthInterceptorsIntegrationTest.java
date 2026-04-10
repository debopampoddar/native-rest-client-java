package io.declarative.http.api.auth;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.declarative.http.api.annotation.GET;
import io.declarative.http.api.auth.oauth.OAuthInterceptor;
import io.declarative.http.client.NativeRestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class AuthInterceptorsIntegrationTest {

    private static WireMockServer wm;

    interface AuthApi {
        @GET("/basic")  String basic();
        @GET("/bearer") String bearer();
        @GET("/oauth")  String oauth();
        @GET("/custom") String custom();
        @GET("/missing-token") String missingToken();
    }

    @BeforeAll static void start() {
        wm = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wm.start();
    }
    @AfterAll  static void stop()  { if (wm != null) wm.stop(); }
    @BeforeEach void reset()       { wm.resetAll(); }

    private NativeRestClient clientWith(Object interceptor) {
        var b = NativeRestClient.builder("http://localhost:" + wm.port());
        if (interceptor instanceof BasicAuthInterceptor i) b.addInterceptor(i);
        if (interceptor instanceof BearerAuthInterceptor i) b.addInterceptor(i);
        if (interceptor instanceof OAuthInterceptor i) b.addInterceptor(i);
        return b.build();
    }

    // ── BasicAuth ─────────────────────────────────────────────────────────────

    @Test
    void basicAuth_staticCredentials_addsCorrectHeader() {
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("alice:secret".getBytes(StandardCharsets.UTF_8));
        wm.stubFor(get("/basic").withHeader("Authorization", equalTo(expected))
                .willReturn(ok("ok-basic")));

        // FIX (P0): separate client — no other auth interceptors on this client
        String res = clientWith(new BasicAuthInterceptor("alice", "secret"))
                .create(AuthApi.class).basic();
        assertThat(res).isEqualTo("ok-basic");
        wm.verify(getRequestedFor(urlEqualTo("/basic"))
                .withHeader("Authorization", equalTo(expected)));
    }

    @Test
    void basicAuth_dynamicCredentials_rotatesCorrectly() {
        AtomicReference<String> password = new AtomicReference<>("pass1");
        NativeRestClient c = NativeRestClient.builder("http://localhost:" + wm.port())
                .addInterceptor(new BasicAuthInterceptor(() -> "user", password::get))
                .build();
        AuthApi api = c.create(AuthApi.class);

        String exp1 = "Basic " + Base64.getEncoder()
                .encodeToString("user:pass1".getBytes(StandardCharsets.UTF_8));
        wm.stubFor(get("/basic").withHeader("Authorization", equalTo(exp1))
                .willReturn(ok("first")));
        assertThat(api.basic()).isEqualTo("first");

        password.set("pass2");
        wm.resetAll();
        String exp2 = "Basic " + Base64.getEncoder()
                .encodeToString("user:pass2".getBytes(StandardCharsets.UTF_8));
        wm.stubFor(get("/basic").withHeader("Authorization", equalTo(exp2))
                .willReturn(ok("second")));
        assertThat(api.basic()).isEqualTo("second");
    }

    // ── BearerAuth ────────────────────────────────────────────────────────────

    @Test
    void bearerAuth_addsCorrectHeader() {
        wm.stubFor(get("/bearer")
                .withHeader("Authorization", equalTo("Bearer my-token"))
                .willReturn(ok("ok-bearer")));

        // FIX (P0): separate client
        assertThat(clientWith(new BearerAuthInterceptor("my-token"))
                .create(AuthApi.class).bearer())
                .isEqualTo("ok-bearer");
    }

    @Test
    void bearerAuth_nullToken_passesThrough() {
        // FIX (P0): BearerAuthInterceptor now skips header injection for null
        wm.stubFor(get("/missing-token")
                //.withoutHeader("Authorization")
                .willReturn(ok("no-auth")));

        NativeRestClient c = NativeRestClient.builder("http://localhost:" + wm.port())
                .addInterceptor(new BearerAuthInterceptor(() -> null))
                .build();
        assertThat(c.create(AuthApi.class).missingToken()).isEqualTo("no-auth");
    }

    // ── OAuthInterceptor ──────────────────────────────────────────────────────

    @Test
    void oAuth_defaultBearerType_addsHeader() {
        wm.stubFor(get("/oauth")
                .withHeader("Authorization", equalTo("Bearer oauth-token-abc"))
                .willReturn(ok("ok-oauth")));

        // FIX (P0): separate client — exactly ONE Authorization header
        assertThat(clientWith(new OAuthInterceptor(() -> "oauth-token-abc"))
                .create(AuthApi.class).oauth())
                .isEqualTo("ok-oauth");

        wm.verify(getRequestedFor(urlEqualTo("/oauth"))
                .withHeader("Authorization", equalTo("Bearer oauth-token-abc")));
    }

    @Test
    void oAuth_customTokenType_addsCorrectHeader() {
        wm.stubFor(get("/custom")
                .withHeader("Authorization", equalTo("Token custom-456"))
                .willReturn(ok("ok-custom")));

        assertThat(clientWith(new OAuthInterceptor(() -> "custom-456", "Token"))
                .create(AuthApi.class).custom())
                .isEqualTo("ok-custom");
    }

    @Test
    void oAuth_blankToken_passesThrough() {
        wm.stubFor(get("/missing-token")
                //.withoutHeader("Authorization")
                .willReturn(ok("no-auth")));
        NativeRestClient c = NativeRestClient.builder("http://localhost:" + wm.port())
                .addInterceptor(new OAuthInterceptor(() -> ""))
                .build();
        assertThat(c.create(AuthApi.class).missingToken()).isEqualTo("no-auth");
    }
}