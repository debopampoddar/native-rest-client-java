package io.declarative.http.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.declarative.http.api.annotation.Body;
import io.declarative.http.api.annotation.DELETE;
import io.declarative.http.api.annotation.Field;
import io.declarative.http.api.annotation.FormUrlEncoded;
import io.declarative.http.api.annotation.GET;
import io.declarative.http.api.annotation.Header;
import io.declarative.http.api.annotation.HeaderMap;
import io.declarative.http.api.annotation.Headers;
import io.declarative.http.api.annotation.PATCH;
import io.declarative.http.api.annotation.POST;
import io.declarative.http.api.annotation.PUT;
import io.declarative.http.api.annotation.Path;
import io.declarative.http.api.annotation.Query;
import io.declarative.http.api.annotation.QueryMap;
import io.declarative.http.api.annotation.Url;
import io.declarative.http.api.interceptors.HttpExchangeInterceptor;
import io.declarative.http.error.ApiException;
import io.declarative.http.error.RestClientException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
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

    interface MutationApi {
        @PUT("/users/{id}")
        String updateUser(@Path("id") long id, @Body String body);

        @DELETE("/users/{id}")
        void deleteUser(@Path("id") long id);

        @PATCH("/users/{id}")
        String patchUser(@Path("id") long id, @Body String patch);
    }

    interface HeaderAnnotationApi {
        @GET("/items")
        String getWithSingleHeader(@Header("X-Custom") String value);

        @Headers({"Accept: application/json", "X-Version: 2"})
        @GET("/items")
        String getWithStaticHeaders();
    }

    interface EnvelopeApi {
        @GET("/users/{id}")
        HttpResponseEnvelope<String> getUserEnvelope(@Path("id") long id);

        @GET("/ping")
        HttpResponseEnvelope<String> pingEnvelope();
    }

    interface VoidApi {
        @DELETE("/users/{id}")
        void delete(@Path("id") long id);

        @GET("/ping")
        void ping();
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

    @Test
    void put_withBody_sendsCorrectMethodAndBody() {
        wm.stubFor(put("/users/10")
                .withRequestBody(equalToJson("{\"name\":\"Bob\"}"))   // tolerant of whitespace
                .willReturn(ok("updated")));
        assertThat(client.create(MutationApi.class).updateUser(10, "{\"name\":\"Bob\"}"))
                .isEqualTo("updated");
    }

// ── @DELETE ───────────────────────────────────────────────────────────────

    @Test
    void delete_void_noExceptionOn204() {
        wm.stubFor(delete("/users/5").willReturn(aResponse().withStatus(204)));
        client.create(VoidApi.class).delete(5); // must not throw
    }

    @Test
    void delete_void_throwsApiExceptionOn404() {
        wm.stubFor(delete("/users/999").willReturn(notFound().withBody("missing")));
        assertThatThrownBy(() -> client.create(VoidApi.class).delete(999))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatusCode()).isEqualTo(404));
    }

// ── @PATCH ────────────────────────────────────────────────────────────────

    @Test
    void patch_sendsCorrectMethod() {
        wm.stubFor(patch(urlPathEqualTo("/users/7"))
                .withRequestBody(containing("admin"))
                .willReturn(ok("patched")));
        assertThat(client.create(MutationApi.class).patchUser(7, "{\"role\":\"admin\"}"))
                .isEqualTo("patched");
    }

// ── @Header ───────────────────────────────────────────────────────────────

    @Test
    void header_annotation_injectsHeaderValue() {
        wm.stubFor(get("/items")
                .withHeader("X-Custom", equalTo("my-value"))
                .willReturn(ok("ok")));
        assertThat(client.create(HeaderAnnotationApi.class).getWithSingleHeader("my-value"))
                .isEqualTo("ok");
    }

// ── @Headers (static) ─────────────────────────────────────────────────────

    @Test
    void staticHeaders_annotation_sendsAllDeclaredHeaders() {
        wm.stubFor(get("/items")
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("X-Version", equalTo("2"))
                .willReturn(ok("versioned")));
        assertThat(client.create(HeaderAnnotationApi.class).getWithStaticHeaders())
                .isEqualTo("versioned");
    }

// ── HttpResponseEnvelope ──────────────────────────────────────────────────

    @Test
    void envelope_success_returnsStatusBodyAndHeaders() {
        wm.stubFor(get("/users/1")
                .willReturn(aResponse().withStatus(200).withBody("alice").withHeader("X-Id", "1")));
        HttpResponseEnvelope<String> env = client.create(EnvelopeApi.class).getUserEnvelope(1L);
        assertThat(env.status()).isEqualTo(200);
        assertThat(env.isSuccessful()).isTrue();
        assertThat(env.body()).isEqualTo("alice");
        assertThat(env.headers().firstValue("x-id")).contains("1");
    }

    @Test
    void envelope_notFound_returnsEnvelopeWithStatus404_noApiException() {
        wm.stubFor(get("/users/99").willReturn(aResponse().withStatus(404).withBody("not found")));
        HttpResponseEnvelope<String> env = client.create(EnvelopeApi.class).getUserEnvelope(99L);
        assertThat(env.status()).isEqualTo(404);
        assertThat(env.isSuccessful()).isFalse();
        // No ApiException thrown — caller decides how to handle the error
    }

    @Test
    void envelope_serverError_returnsEnvelopeWithStatus500_noApiException() {
        wm.stubFor(get("/users/0").willReturn(aResponse().withStatus(500).withBody("error")));
        HttpResponseEnvelope<String> env = client.create(EnvelopeApi.class).getUserEnvelope(0L);
        assertThat(env.status()).isEqualTo(500);
        assertThat(env.isSuccessful()).isFalse();
    }

    @Test
    void envelope_noContent_returnsNullBody() {
        wm.stubFor(get("/ping").willReturn(aResponse().withStatus(204)));
        HttpResponseEnvelope<String> env = client.create(EnvelopeApi.class).pingEnvelope();
        assertThat(env.status()).isEqualTo(204);
        assertThat(env.body()).isNull();
    }

// ── void return ───────────────────────────────────────────────────────────

    @Test
    void voidGet_200_noException() {
        wm.stubFor(get("/ping").willReturn(ok()));
        client.create(VoidApi.class).ping(); // must not throw
    }

// ── Exchange interceptor ──────────────────────────────────────────────────

    @Test
    void exchangeInterceptor_isInvoked() {
        wm.stubFor(get("/users/1").willReturn(ok("body")));
        java.util.concurrent.atomic.AtomicBoolean invoked =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        NativeRestClient c = NativeRestClient.builder("http://localhost:" + wm.port())
                .addExchangeInterceptor(new HttpExchangeInterceptor() {
                    @Override
                    public <T> HttpResponse<T> intercept(HttpRequest request, ExchangeChain<T> chain) throws IOException, InterruptedException {
                        invoked.set(true);
                        return chain.proceed(request);
                    }
                })
                .build();
        c.create(TestApi.class).getUser(1);
        assertThat(invoked).isTrue();
    }

    @Test
    void exchangeInterceptors_executedInRegistrationOrder() {
        wm.stubFor(get("/users/1").willReturn(ok("body")));
        java.util.List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
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
                .build();
        c.create(TestApi.class).getUser(1);
        assertThat(order).containsExactly("first", "second");
    }

    @Test
    void exchangeInterceptor_canModifyRequest() {
        wm.stubFor(get("/users/1").withHeader("X-Injected", equalTo("yes")).willReturn(ok("ok")));
        NativeRestClient c = NativeRestClient.builder("http://localhost:" + wm.port())
                .addExchangeInterceptor(new HttpExchangeInterceptor() {
                    @Override
                    public <T> HttpResponse<T> intercept(HttpRequest request, ExchangeChain<T> chain) throws IOException, InterruptedException {
                        java.net.http.HttpRequest modified = java.net.http.HttpRequest.newBuilder(request,
                                (k, v) -> true).header("X-Injected", "yes").build();
                        return chain.proceed(modified);
                    }
                })
                .build();
        assertThat(c.create(TestApi.class).getUser(1)).isEqualTo("ok");
    }

// ── Builder: executor ─────────────────────────────────────────────────────

    @Test
    void builder_customExecutor_usedForHttpClient() {
        wm.stubFor(get("/users/1").willReturn(ok("ok")));
        NativeRestClient c = NativeRestClient.builder("http://localhost:" + wm.port())
                .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
                .build();
        assertThat(c.create(TestApi.class).getUser(1)).isEqualTo("ok");
    }
}
