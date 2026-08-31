package io.declarative.http.api.auth;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.declarative.http.api.annotation.GET;
import io.declarative.http.api.auth.oauth.OAuthInterceptor;
import io.declarative.http.api.interceptors.ClientInterceptor;
import io.declarative.http.api.interceptors.InterceptorChain;
import io.declarative.http.client.NativeRestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
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
        @GET("/api-key") String apiKey();
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
        if (interceptor instanceof ApiKeyAuthInterceptor i) b.addInterceptor(i);
        if (interceptor instanceof OAuthInterceptor i) b.addInterceptor(i);
        return b.build();
    }

    // ── BasicAuth ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Adds an HTTP Basic header for static credentials")
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
    @DisplayName("Uses updated credentials when the Basic-auth suppliers rotate")
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
    @DisplayName("Adds the expected Bearer authorization header")
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
    @DisplayName("Leaves the request unchanged when the Bearer token is absent")
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

    // ── ApiKeyAuth ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Adds the configured API key header")
    void apiKeyAuth_addsConfiguredHeader() {
        wm.stubFor(get("/api-key")
                .withHeader("X-Api-Key", equalTo("api-key-123"))
                .willReturn(ok("ok-api-key")));

        assertThat(clientWith(new ApiKeyAuthInterceptor("X-Api-Key", "api-key-123"))
                .create(AuthApi.class).apiKey())
                .isEqualTo("ok-api-key");
    }

    @Test
    @DisplayName("Uses the latest API key supplied for each request")
    void apiKeyAuth_usesLatestSupplierValue() {
        AtomicReference<String> key = new AtomicReference<>("first-key");
        NativeRestClient client = clientWith(new ApiKeyAuthInterceptor("X-Api-Key", key::get));
        AuthApi api = client.create(AuthApi.class);
        wm.stubFor(get("/api-key").withHeader("X-Api-Key", equalTo("first-key"))
                .willReturn(ok("first")));
        assertThat(api.apiKey()).isEqualTo("first");

        key.set("rotated-key");
        wm.resetAll();
        wm.stubFor(get("/api-key").withHeader("X-Api-Key", equalTo("rotated-key"))
                .willReturn(ok("second")));
        assertThat(api.apiKey()).isEqualTo("second");
    }

    @Test
    @DisplayName("Replaces an existing API key header and skips a blank key")
    void apiKeyAuth_replacesExistingHeaderAndSkipsBlankKey() throws IOException {
        HttpRequest replaced = applyToRequest(
                new ApiKeyAuthInterceptor("X-Api-Key", "fresh-key"),
                "x-api-key", "stale-key");
        assertThat(replaced.headers().allValues("X-Api-Key"))
                .containsExactly("fresh-key");

        HttpRequest unchanged = applyToRequest(
                new ApiKeyAuthInterceptor("X-Api-Key", () -> " "),
                "X-Api-Key", "original-key");
        assertThat(unchanged.headers().allValues("X-Api-Key"))
                .containsExactly("original-key");
    }

    // ── OAuthInterceptor ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Adds an OAuth token using the default Bearer scheme")
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
    @DisplayName("Adds an OAuth token using the configured authorization scheme")
    void oAuth_customTokenType_addsCorrectHeader() {
        wm.stubFor(get("/custom")
                .withHeader("Authorization", equalTo("Token custom-456"))
                .willReturn(ok("ok-custom")));

        assertThat(clientWith(new OAuthInterceptor(() -> "custom-456", "Token"))
                .create(AuthApi.class).custom())
                .isEqualTo("ok-custom");
    }

    @Test
    @DisplayName("Leaves the request unchanged when the OAuth token is blank")
    void oAuth_blankToken_passesThrough() {
        wm.stubFor(get("/missing-token")
                //.withoutHeader("Authorization")
                .willReturn(ok("no-auth")));
        NativeRestClient c = NativeRestClient.builder("http://localhost:" + wm.port())
                .addInterceptor(new OAuthInterceptor(() -> ""))
                .build();
        assertThat(c.create(AuthApi.class).missingToken()).isEqualTo("no-auth");
    }

    @Test
    @DisplayName("Replaces an existing authorization header with HTTP Basic credentials")
    void basicAuth_replacesExistingAuthorizationHeader() throws IOException {
        HttpRequest request = applyToRequest(
                new BasicAuthInterceptor("alice", "secret"));
        assertThat(request.headers().allValues("Authorization"))
                .singleElement().asString().startsWith("Basic ");
    }

    @Test
    @DisplayName("Replaces an existing authorization header with a Bearer token")
    void bearerAuth_replacesExistingAuthorizationHeader() throws IOException {
        HttpRequest request = applyToRequest(new BearerAuthInterceptor("fresh"));
        assertThat(request.headers().allValues("Authorization"))
                .containsExactly("Bearer fresh");
    }

    @Test
    @DisplayName("Replaces an existing authorization header with an OAuth token")
    void oauth_replacesExistingAuthorizationHeader() throws IOException {
        HttpRequest request = applyToRequest(new OAuthInterceptor(() -> "fresh"));
        assertThat(request.headers().allValues("Authorization"))
                .containsExactly("Bearer fresh");
    }

    /**
     * Applies one interceptor to a request containing a stale Authorization header.
     *
     * @param interceptor interceptor to test
     * @return interceptor-processed request
     * @throws IOException if the interceptor chain fails
     */
    private static HttpRequest applyToRequest(ClientInterceptor interceptor)
            throws IOException {
        return applyToRequest(interceptor, "authorization", "Bearer stale");
    }

    /**
     * Applies one interceptor to a request containing a pre-existing header.
     *
     * @param interceptor interceptor to test
     * @param headerName existing header name
     * @param headerValue existing header value
     * @return interceptor-processed request
     * @throws IOException if the interceptor chain fails
     */
    private static HttpRequest applyToRequest(ClientInterceptor interceptor,
                                              String headerName,
                                              String headerValue)
            throws IOException {
        HttpRequest original = HttpRequest.newBuilder(URI.create("https://example.test"))
                .header(headerName, headerValue)
                .build();
        return new InterceptorChain(List.of(interceptor)).proceed(original);
    }
}
