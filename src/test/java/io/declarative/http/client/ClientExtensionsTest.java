package io.declarative.http.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.declarative.http.api.annotation.GET;
import io.declarative.http.api.annotation.Headers;
import io.declarative.http.api.annotation.Multipart;
import io.declarative.http.api.annotation.POST;
import io.declarative.http.api.annotation.Part;
import io.declarative.http.error.ErrorDecoder;
import io.declarative.http.error.RestClientException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientExtensionsTest {

    private static WireMockServer wm;

    interface OverridesApi {
        @GET("/overrides")
        @Headers("X-Mode: static")
        String get(RequestOptions options);
    }

    interface MultipartApi {
        @POST("/upload")
        @Multipart
        String upload(@Part("description") String description,
                      @Part("file") MultipartPart file);
    }

    interface ErrorsApi {
        @GET("/failure")
        String failure();

        @GET("/failure")
        java.util.concurrent.CompletableFuture<String> failureAsync();
    }

    interface InvalidApi {
        @GET("/invalid")
        @POST("/invalid")
        String invalid();
    }

    interface RequestOptionsNotFinalApi {
        @GET("/invalid")
        String invalid(RequestOptions options, @io.declarative.http.api.annotation.Query("q") String query);
    }

    private static final class RemoteFailure extends RuntimeException {
        private final int status;

        private RemoteFailure(int status, String message) {
            super(message);
            this.status = status;
        }
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
    @DisplayName("Request options replace static headers and override the client timeout")
    void requestOptions_replaceStaticHeadersAndOverrideClientTimeout() {
        wm.stubFor(get("/overrides")
                .withHeader("X-Mode", equalTo("dynamic"))
                .withHeader("X-Correlation-Id", equalTo("correlation-1"))
                .willReturn(ok("ok")));

        RequestOptions options = RequestOptions.builder()
                .header("X-Mode", "dynamic")
                .header("X-Correlation-Id", "correlation-1")
                .timeout(Duration.ofSeconds(1))
                .build();
        try (NativeRestClient client = NativeRestClient.builder(baseUrl())
                .requestTimeout(Duration.ofMillis(50))
                .build()) {
            assertThat(client.create(OverridesApi.class).get(options)).isEqualTo("ok");
        }
        wm.verify(getRequestedFor(urlEqualTo("/overrides"))
                .withHeader("X-Mode", equalTo("dynamic")));
    }

    @Test
    @DisplayName("Request options declaration must be unannotated and final")
    void requestOptions_declarationMustBeUnannotatedAndFinal() {
        try (NativeRestClient client = NativeRestClient.builder(baseUrl()).build()) {
            assertThatThrownBy(() -> client.create(RequestOptionsNotFinalApi.class))
                    .isInstanceOf(RestClientException.class)
                    .hasMessageContaining("must be the final parameter");
        }
    }

    @Test
    @DisplayName("Service declaration validation happens when creating the proxy")
    void serviceDeclarationValidation_happensWhenCreatingTheProxy() {
        try (NativeRestClient client = NativeRestClient.builder(baseUrl()).build()) {
            assertThatThrownBy(() -> client.create(InvalidApi.class))
                    .isInstanceOf(RestClientException.class)
                    .hasMessageContaining("exactly one HTTP verb");
        }
    }

    @Test
    @DisplayName("Custom error decoder receives bounded body and response headers")
    void customErrorDecoder_receivesBoundedBodyAndResponseHeaders() {
        wm.stubFor(get("/failure").willReturn(aResponse().withStatus(422)
                .withHeader("X-Error-Code", "validation")
                .withBody("invalid request")));
        ErrorDecoder decoder = (status, headers, body) -> new RemoteFailure(
                status, headers.firstValue("X-Error-Code").orElse("unknown") + ":" + body);

        try (NativeRestClient client = NativeRestClient.builder(baseUrl())
                .errorDecoder(decoder)
                .build()) {
            assertThatThrownBy(() -> client.create(ErrorsApi.class).failure())
                    .isInstanceOf(RemoteFailure.class)
                    .satisfies(error -> {
                        RemoteFailure remote = (RemoteFailure) error;
                        assertThat(remote.status).isEqualTo(422);
                        assertThat(remote).hasMessage("validation:invalid request");
                    });
        }
    }

    @Test
    @DisplayName("Custom error decoder is applied to asynchronous service methods")
    void customErrorDecoder_isAppliedToAsynchronousServiceMethods() {
        wm.stubFor(get("/failure").willReturn(aResponse().withStatus(404)
                .withBody("missing")));
        try (NativeRestClient client = NativeRestClient.builder(baseUrl())
                .errorDecoder((status, headers, body) -> new RemoteFailure(status, body))
                .build()) {
            assertThatThrownBy(() -> client.create(ErrorsApi.class).failureAsync().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(RemoteFailure.class)
                    .cause().hasMessage("missing");
        }
    }

    @Test
    @DisplayName("Multipart request emits text binary filename and content type")
    void multipartRequest_emitsTextBinaryFilenameAndContentType() {
        wm.stubFor(post("/upload").willReturn(ok("uploaded")));
        MultipartPart file = MultipartPart.ofBytes(
                "file-content".getBytes(StandardCharsets.UTF_8), "sample.txt", "text/plain");

        try (NativeRestClient client = NativeRestClient.builder(baseUrl()).build()) {
            assertThat(client.create(MultipartApi.class).upload("A note", file))
                    .isEqualTo("uploaded");
        }

        wm.verify(postRequestedFor(urlEqualTo("/upload"))
                .withHeader("Content-Type", containing("multipart/form-data; boundary="))
                .withRequestBody(containing("name=\"description\""))
                .withRequestBody(containing("A note"))
                .withRequestBody(containing("name=\"file\"; filename=\"sample.txt\""))
                .withRequestBody(containing("Content-Type: text/plain"))
                .withRequestBody(containing("file-content")));
    }

    @Test
    @DisplayName("Multipart part rejects header injection in filenames and content types")
    void multipartPart_rejectsHeaderInjectionInFilenamesAndContentTypes() {
        assertThatThrownBy(() -> MultipartPart.ofBytes(new byte[0], "bad\r\nname", "text/plain"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fileName");
        assertThatThrownBy(() -> MultipartPart.ofBytes(new byte[0], "safe.txt", "text/plain\r\nX: y"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contentType");
    }

    /**
     * Builds the local WireMock root URL used by all test clients.
     *
     * @return HTTP base URL for the current test server
     */
    private static String baseUrl() {
        return "http://localhost:" + wm.port();
    }
}
