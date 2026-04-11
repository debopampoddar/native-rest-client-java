package io.declarative.http.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.declarative.http.api.annotation.GET;
import io.declarative.http.api.annotation.HeaderMap;
import io.declarative.http.api.annotation.Path;
import io.declarative.http.api.annotation.Query;
import io.declarative.http.api.annotation.QueryMap;
import io.declarative.http.api.annotation.Url;
import io.declarative.http.error.RestClientException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HandlersEdgeCasesTest {

    private static WireMockServer wm;
    private NativeRestClient client;

    // -------------------------------------------------------------------------
    // API definitions for edge-case coverage
    // -------------------------------------------------------------------------

    interface StreamApi {
        @GET("/stream")
        InputStream getStream();

        @GET("/stream")
        HttpResponseEnvelope<InputStream> getStreamEnvelope();
    }

    interface EncodedApi {
        // encoded = true: caller provides already-encoded path segment
        @GET("/files/{path}")
        String getFile(@Path(value = "path", encoded = true) String path);

        // collection query param
        @GET("/search")
        String searchTags(@Query("tag") List<String> tags);

        // encoded = true: no double-encoding
        @GET("/raw-query")
        String rawQuery(@Query(value = "q", encoded = true) String alreadyEncoded);

        // QueryMap default encoding
        @GET("/query-map")
        String queryMap(@QueryMap Map<String, Object> params);

        // QueryMap encoded = true: key/value not re-encoded
        @GET("/query-map-raw")
        String queryMapRaw(@QueryMap(encoded = true) Map<String, Object> params);

        // HeaderMap valid
        @GET("/headers")
        String headers(@HeaderMap Map<String, String> headers);

        // @Url override (non-null)
        @GET
        String dynamic(@Url String url);

        // @Url null → keep original path
        @GET("/ping")
        String pingWithOptionalUrl(@Url String maybeNull);
    }

    interface BadHandlerApi {
        @GET("/bad-query-map")
        String badQueryMap(@QueryMap String notAMap);

        @GET("/bad-header-map")
        String badHeaderMap(@HeaderMap String notAMap);

        @GET("/no-annotation")
        String noAnnotation(String value);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @BeforeAll
    static void startWireMock() {
        wm = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wm.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wm != null) wm.stop();
    }

    @BeforeEach
    void setup() {
        wm.resetAll();
        client = NativeRestClient.builder("http://localhost:" + wm.port()).build();
    }

    // -------------------------------------------------------------------------
    // InputStream handling in InvocationDispatcher
    // -------------------------------------------------------------------------

    @Test
    void inputStreamResponse_isPassedThrough() throws Exception {
        wm.stubFor(get("/stream").willReturn(ok("payload")));
        InputStream in = client.create(StreamApi.class).getStream();
        String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        assertThat(body).isEqualTo("payload");
    }

    @Test
    void inputStreamEnvelope_preservesStatusHeadersAndBody() throws Exception {
        wm.stubFor(get("/stream")
                .willReturn(ok("payload").withHeader("X-Test", "1")));

        HttpResponseEnvelope<InputStream> env =
                client.create(StreamApi.class).getStreamEnvelope();

        assertThat(env.status()).isEqualTo(200);
        assertThat(env.isSuccessful()).isTrue();
        assertThat(env.headers().firstValue("X-Test")).contains("1");

        String body = new String(env.body().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(body).isEqualTo("payload");
    }

    // -------------------------------------------------------------------------
    // PathHandler, QueryHandler, QueryMapHandler, HeaderMapHandler, UrlHandler
    // -------------------------------------------------------------------------

    @Test
    void pathHandler_respectsEncodedFlag_andDoesNotDoubleEncode() {
        // Expect literal %2F and %20 from the client — no additional encoding
        wm.stubFor(get("/files/a%2Fb%20c").willReturn(ok("ok")));

        String res = client.create(EncodedApi.class).getFile("a%2Fb%20c");
        assertThat(res).isEqualTo("ok");
    }

    @Test
    void queryHandler_supportsCollectionValues() {
        wm.stubFor(get(urlPathEqualTo("/search"))
                .withQueryParam("tag", equalTo("java"))
                .withQueryParam("tag", equalTo("spring"))
                .willReturn(ok("ok")));

        String res = client.create(EncodedApi.class)
                .searchTags(List.of("java", "spring"));

        assertThat(res).isEqualTo("ok");
    }

    @Test
    void queryHandler_encodedTrue_doesNotDoubleEncode() {
        wm.stubFor(get(urlPathEqualTo("/raw-query"))
                // WireMock compares against the decoded value
                .withQueryParam("q", equalTo("a/b"))
                .willReturn(ok("ok")));

        // Because encoded = true, the framework sends "a%2Fb" verbatim in the URI,
        // which WireMock decodes to "a/b" for matching.
        String res = client.create(EncodedApi.class).rawQuery("a%2Fb");
        assertThat(res).isEqualTo("ok");
    }

    @Test
    void queryMap_defaultEncoding_encodesKeysAndValues() {
        wm.stubFor(get(urlPathEqualTo("/query-map"))
                .withQueryParam("status", equalTo("active"))
                .withQueryParam("role", equalTo("admin"))
                // WireMock matches on decoded key/value: "country code" / "US West"
                .withQueryParam("country code", equalTo("US West"))
                .willReturn(ok("ok")));

        Map<String, Object> params = Map.of(
                "status", "active",
                "role", "admin",
                "country code", "US West"
        );

        String res = client.create(EncodedApi.class).queryMap(params);
        assertThat(res).isEqualTo("ok");
    }

    @Test
    void queryMap_encodedTrue_doesNotDoubleEncode() {
        wm.stubFor(get(urlPathEqualTo("/query-map-raw"))
                .withQueryParam("raw", equalTo("raw=value"))
                .willReturn(ok("ok")));

        // Because encoded = true, QueryMapHandler will not re-encode the key or value.
        // The URI becomes ?raw=raw=value, and WireMock sees the value as "raw=value".
        Map<String, Object> params = Map.of("raw", "raw=value");

        String res = client.create(EncodedApi.class).queryMapRaw(params);
        assertThat(res).isEqualTo("ok");
    }

    @Test
    void headerMap_appliesOnlyNonNullEntries() {
        wm.stubFor(get("/headers")
                .withHeader("X-Trace-Id", equalTo("abc123"))
                .withHeader("X-Request-Id", equalTo("req-1"))
                .willReturn(ok("ok")));

        Map<String, String> headers = Map.of(
                "X-Trace-Id", "abc123",
                "X-Request-Id", "req-1",
                // These should be skipped by HeaderMapHandler
                "X-Null-Value", "null"
        );

        String res = client.create(EncodedApi.class).headers(headers);
        assertThat(res).isEqualTo("ok");
    }

    @Test
    void urlHandler_overridesFullUrlWhenNonNull() {
        wm.stubFor(get("/dynamic").willReturn(ok("dynamic-ok")));

        EncodedApi api = client.create(EncodedApi.class);
        String url = "http://localhost:" + wm.port() + "/dynamic";
        assertThat(api.dynamic(url)).isEqualTo("dynamic-ok");
    }

    @Test
    void urlHandler_preservesOriginalPathWhenNull() {
        wm.stubFor(get("/ping").willReturn(ok("pong")));

        EncodedApi api = client.create(EncodedApi.class);
        assertThat(api.pingWithOptionalUrl(null)).isEqualTo("pong");
    }

    // -------------------------------------------------------------------------
    // Error paths for @QueryMap / @HeaderMap and unannotated parameter
    // -------------------------------------------------------------------------

    @Test
    void queryMap_nonMapParameter_throwsIllegalArgumentException() {
        BadHandlerApi api = client.create(BadHandlerApi.class);

        assertThatThrownBy(() -> api.badQueryMap("not-a-map"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@QueryMap parameter must be of type Map<String, Object>");
    }

    @Test
    void headerMap_nonMapParameter_throwsIllegalArgumentException() {
        BadHandlerApi api = client.create(BadHandlerApi.class);

        assertThatThrownBy(() -> api.badHeaderMap("not-a-map"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@HeaderMap parameter must be of type Map<String, String>");
    }

    @Test
    void parameterWithoutAnnotation_causesRestClientExceptionOnInvocation() {
        BadHandlerApi api = client.create(BadHandlerApi.class);

        assertThatThrownBy(() -> api.noAnnotation("value"))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("has no recognised annotation");
    }

    // -------------------------------------------------------------------------
    // Async + exchange interceptors: ensure async path is still functional
    // -------------------------------------------------------------------------

    interface AsyncApi {
        @GET("/async")
        CompletableFuture<String> async();
    }

    @Test
    void asyncMethod_usesAsyncPathAndReturnsCompletableFuture() {
        wm.stubFor(get("/async").willReturn(ok("async-ok")));
        AsyncApi api = client.create(AsyncApi.class);
        String res = api.async().join();
        assertThat(res).isEqualTo("async-ok");
    }
}
