package io.declarative.http.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.declarative.http.api.annotation.Body;
import io.declarative.http.api.annotation.Field;
import io.declarative.http.api.annotation.FormUrlEncoded;
import io.declarative.http.api.annotation.GET;
import io.declarative.http.api.annotation.HeaderMap;
import io.declarative.http.api.annotation.POST;
import io.declarative.http.api.annotation.Path;
import io.declarative.http.api.annotation.Query;
import io.declarative.http.api.annotation.QueryMap;
import io.declarative.http.api.annotation.Url;
import io.declarative.http.error.ApiException;
import io.declarative.http.error.RestClientException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeRestClientTest {

    private static WireMockServer wm;
    private NativeRestClient client;

    // ── API interface ─────────────────────────────────────────────────────────
    interface TestApi {
        @GET("/users/{id}")
        String getUser(@Path("id") long id);

        @GET("/search")
        String search(@Query("q") String query, @Query("page") Integer page);

        @GET("/filter")
        String filter(@QueryMap Map<String, String> params);

        @GET("/data")
        String dataWithHeaders(@HeaderMap Map<String, String> headers);

        @POST("/users")
        String createUser(@Body String body);

        @GET("/users/{id}")
        CompletableFuture<String> getUserAsync(@Path("id") long id);

        @GET
        String dynamicUrl(@Url String url);

        @FormUrlEncoded
        @POST("/login")
        String login(@Field("username") String username, @Field("password") String password);
    }

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

    @Test
    void get_withPathParam_returnsBody() {
        wm.stubFor(get("/users/42").willReturn(okJson("{\"id\":42}")));
        assertThat(client.create(TestApi.class).getUser(42)).contains("42");
    }

    @Test
    void get_withQueryParams_sendsCorrectUrl() {
        wm.stubFor(get(urlPathEqualTo("/search")).withQueryParam("q", equalTo("java")).withQueryParam("page", equalTo("1")).willReturn(ok("results")));
        assertThat(client.create(TestApi.class).search("java", 1)).isEqualTo("results");
    }

    @Test
    void get_withQueryMap_sendsAllParams() {
        wm.stubFor(get(urlPathEqualTo("/filter")).withQueryParam("status", equalTo("active")).withQueryParam("role", equalTo("admin")).willReturn(ok("filtered")));
        assertThat(client.create(TestApi.class).filter(Map.of("status", "active", "role", "admin"))).isEqualTo("filtered");
    }

    @Test
    void get_withHeaderMap_sendsAllHeaders() {
        wm.stubFor(get("/data").withHeader("X-Trace-Id", equalTo("abc123")).willReturn(ok("ok")));
        assertThat(client.create(TestApi.class).dataWithHeaders(Map.of("X-Trace-Id", "abc123"))).isEqualTo("ok");
    }

    @Test
    void post_withBody_sendsJson() {
        wm.stubFor(post("/users").withHeader("Content-Type", containing("application/json")).willReturn(ok("created")));
        assertThat(client.create(TestApi.class).createUser("{\"name\":\"Alice\"}")).isEqualTo("created");
    }

    @Test
    void async_returnsCompletableFuture() {
        wm.stubFor(get("/users/99").willReturn(okJson("{\"id\":99}")));
        String result = client.create(TestApi.class).getUserAsync(99).join();
        assertThat(result).contains("99");
    }

    @Test
    void dynamicUrl_overridesBaseUrlAndPath() {
        wm.stubFor(get("/external/resource").willReturn(ok("dynamic")));
        String url = "http://localhost:" + wm.port() + "/external/resource";
        assertThat(client.create(TestApi.class).dynamicUrl(url)).isEqualTo("dynamic");
    }

    @Test
    void formUrlEncoded_sendsFormBody() {
        wm.stubFor(post("/login").withHeader("Content-Type", equalTo("application/x-www-form-urlencoded")).withRequestBody(containing("username=alice")).withRequestBody(containing("password=secret")).willReturn(ok("token=abc")));
        assertThat(client.create(TestApi.class).login("alice", "secret")).isEqualTo("token=abc");
    }

    @Test
    void get_404_throwsApiException() {
        wm.stubFor(get("/users/999").willReturn(notFound().withBody("Not found")));
        assertThatThrownBy(() -> client.create(TestApi.class).getUser(999)).isInstanceOf(ApiException.class).satisfies(ex -> {
            ApiException e = (ApiException) ex;
            assertThat(e.getStatusCode()).isEqualTo(404);
            assertThat(e.isClientError()).isTrue();
            assertThat(e.isServerError()).isFalse();
        });
    }

    @Test
    void get_500_throwsApiException_isServerError() {
        wm.stubFor(get("/users/0").willReturn(aResponse().withStatus(500).withBody("Internal error")));
        assertThatThrownBy(() -> client.create(TestApi.class).getUser(0)).isInstanceOf(ApiException.class).satisfies(ex -> {
            ApiException e = (ApiException) ex;
            assertThat(e.isServerError()).isTrue();
            assertThat(e.isClientError()).isFalse();
        });
    }

    @Test
    void nullPathParam_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> {
            interface NullApi {
                @GET("/u/{id}")
                String get(@Path("id") String id);
            }
            NativeRestClient.builder("http://localhost:" + wm.port()).build().create(NullApi.class).get(null);
        }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("id");
    }

    @Test
    void emptyInterface_throwsRestClientException() {
        interface EmptyApi {
        }
        assertThatThrownBy(() -> client.create(EmptyApi.class)).isInstanceOf(RestClientException.class).hasMessageContaining("declares no methods");
    }
}
