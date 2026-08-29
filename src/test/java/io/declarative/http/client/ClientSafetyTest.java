package io.declarative.http.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.declarative.http.api.annotation.Body;
import io.declarative.http.api.annotation.Field;
import io.declarative.http.api.annotation.FormUrlEncoded;
import io.declarative.http.api.annotation.GET;
import io.declarative.http.api.annotation.Header;
import io.declarative.http.api.annotation.POST;
import io.declarative.http.api.annotation.Query;
import io.declarative.http.error.RestClientException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientSafetyTest {

    private static WireMockServer wm;

    interface SafetyApi {
        @GET("/slow")
        String slow();

        @GET("/slow")
        CompletableFuture<String> slowAsync();

        @GET("/user")
        UserPayload user();

        @GET("/user")
        HttpResponseEnvelope<UserPayload> userEnvelope();
    }

    interface ParentApi {
        @GET("/user")
        UserPayload user();
    }

    interface ChildApi extends ParentApi {
        default String localHelper() {
            return "local";
        }
    }

    interface MultipleVerbsApi {
        @GET("/bad")
        @POST("/bad")
        String bad();
    }

    interface MultipleBodiesApi {
        @POST("/bad")
        String bad(@Body String first, @Body String second);
    }

    interface BodyAndFormApi {
        @FormUrlEncoded
        @POST("/bad")
        String bad(@Body String body, @Field("x") String field);
    }

    interface FieldWithoutFormApi {
        @POST("/bad")
        String bad(@Field("x") String field);
    }

    interface MultipleBindingsApi {
        @GET("/bad")
        String bad(@Query("q") @Header("X-Q") String value);
    }

    @SuppressWarnings("rawtypes")
    interface RawFutureApi {
        @GET("/bad")
        CompletableFuture bad();
    }

    @SuppressWarnings("rawtypes")
    interface RawEnvelopeApi {
        @GET("/bad")
        HttpResponseEnvelope bad();
    }

    record UserPayload(String name) {
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
    @DisplayName("Request timeout applies to sync and async calls")
    void requestTimeout_appliesToSyncAndAsyncCalls() {
        wm.stubFor(get("/slow").willReturn(aResponse()
                .withFixedDelay(250).withBody("late")));
        try (NativeRestClient client = NativeRestClient.builder(baseUrl())
                .requestTimeout(Duration.ofMillis(50))
                .build()) {
            SafetyApi api = client.create(SafetyApi.class);

            assertThatThrownBy(api::slow)
                    .isInstanceOf(RestClientException.class)
                    .hasCauseInstanceOf(HttpTimeoutException.class);
            assertThatThrownBy(() -> api.slowAsync().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(RestClientException.class)
                    .cause().hasCauseInstanceOf(HttpTimeoutException.class);
        }
    }

    @Test
    @DisplayName("Request timeout rejects non positive durations")
    void requestTimeout_rejectsNonPositiveDurations() {
        assertThatThrownBy(() -> NativeRestClient.builder(baseUrl())
                .requestTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NativeRestClient.builder(baseUrl())
                .requestTimeout(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Default mapper ignores unknown response fields")
    void defaultMapper_ignoresUnknownResponseFields() {
        wm.stubFor(get("/user").willReturn(okJson(
                "{\"name\":\"Alice\",\"addedLater\":true}")));
        try (NativeRestClient client = NativeRestClient.builder(baseUrl()).build()) {
            assertThat(client.create(SafetyApi.class).user().name())
                    .isEqualTo("Alice");
        }
    }

    @Test
    @DisplayName("Envelope preserves raw error without deserializing as success type")
    void envelope_preservesRawErrorWithoutDeserializingAsSuccessType() {
        wm.stubFor(get("/user").willReturn(aResponse().withStatus(404)
                .withBody("{\"error\":\"missing\"}")));
        try (NativeRestClient client = NativeRestClient.builder(baseUrl()).build()) {
            HttpResponseEnvelope<UserPayload> envelope =
                    client.create(SafetyApi.class).userEnvelope();

            assertThat(envelope.status()).isEqualTo(404);
            assertThat(envelope.body()).isNull();
            assertThat(envelope.errorBody()).isEqualTo("{\"error\":\"missing\"}");
        }
    }

    @Test
    @DisplayName("Envelope truncates oversized error body")
    void envelope_truncatesOversizedErrorBody() {
        wm.stubFor(get("/user").willReturn(aResponse().withStatus(500)
                .withBody("x".repeat(70_000))));
        try (NativeRestClient client = NativeRestClient.builder(baseUrl()).build()) {
            HttpResponseEnvelope<UserPayload> envelope =
                    client.create(SafetyApi.class).userEnvelope();

            assertThat(envelope.errorBody())
                    .endsWith("...[truncated]")
                    .hasSizeLessThan(66_000);
        }
    }

    @Test
    @DisplayName("Proxy supports object inherited and default methods")
    void proxySupportsObjectInheritedAndDefaultMethods() {
        wm.stubFor(get("/user").willReturn(okJson("{\"name\":\"Alice\"}")));
        try (NativeRestClient client = NativeRestClient.builder(baseUrl()).build()) {
            ChildApi proxy = client.create(ChildApi.class);

            assertThat(proxy.equals(proxy)).isTrue();
            assertThat(proxy.hashCode()).isEqualTo(proxy.hashCode());
            assertThat(proxy.toString()).contains(ChildApi.class.getName());
            assertThat(proxy.localHelper()).isEqualTo("local");
            assertThat(proxy.user().name()).isEqualTo("Alice");
        }
    }

    @Test
    @DisplayName("Invalid service declarations fail before network execution")
    void invalidServiceDeclarationsFailBeforeNetworkExecution() {
        try (NativeRestClient client = NativeRestClient.builder(baseUrl()).build()) {
            assertThatThrownBy(() -> client.create(MultipleVerbsApi.class).bad())
                    .isInstanceOf(RestClientException.class)
                    .hasMessageContaining("exactly one HTTP verb");
            assertThatThrownBy(() -> client.create(MultipleBodiesApi.class)
                    .bad("one", "two"))
                    .isInstanceOf(RestClientException.class)
                    .hasMessageContaining("multiple @Body");
            assertThatThrownBy(() -> client.create(BodyAndFormApi.class)
                    .bad("body", "field"))
                    .isInstanceOf(RestClientException.class)
                    .hasMessageContaining("@FormUrlEncoded with @Body");
            assertThatThrownBy(() -> client.create(FieldWithoutFormApi.class).bad("x"))
                    .isInstanceOf(RestClientException.class)
                    .hasMessageContaining("@Field without @FormUrlEncoded");
            assertThatThrownBy(() -> client.create(MultipleBindingsApi.class).bad("x"))
                    .isInstanceOf(RestClientException.class)
                    .hasMessageContaining("exactly one binding annotation");
            assertThatThrownBy(() -> client.create(RawFutureApi.class).bad())
                    .isInstanceOf(RestClientException.class)
                    .hasMessageContaining("CompletableFuture<T>");
            assertThatThrownBy(() -> client.create(RawEnvelopeApi.class).bad())
                    .isInstanceOf(RestClientException.class)
                    .hasMessageContaining("HttpResponseEnvelope<T>");
        }
    }

    private static String baseUrl() {
        return "http://localhost:" + wm.port();
    }
}
