package io.declarative.http.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.declarative.http.api.annotation.Body;
import io.declarative.http.api.annotation.DELETE;
import io.declarative.http.api.annotation.GET;
import io.declarative.http.api.annotation.POST;
import io.declarative.http.api.annotation.Path;
import io.declarative.http.api.annotation.Query;
import io.declarative.http.error.ApiException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeRestClientTest {

    static WireMockServer wireMock;
    static NativeRestClient client;
    static TestApi api;

    interface TestApi {
        @GET("/users/{id}")
        TestUser getUser(@Path("id") long id);

        @GET("/users")
        List<TestUser> listUsers(
                @Query("page") int page,
                @Query("size") int size
        );

        @POST("/users")
        TestUser createUser(@Body TestUser user);

        @DELETE("/users/{id}")
        void deleteUser(@Path("id") long id);
    }

    record TestUser(long id, String name) {}

    @BeforeAll
    static void setup() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();

        client = NativeRestClient
                .builder("http://localhost:" + wireMock.port())
                .build();
        api = client.create(TestApi.class);
    }

    @AfterAll
    static void teardown() {
        wireMock.stop();
    }

    @BeforeEach
    void reset() {
        wireMock.resetAll();
    }

    @Test
    void getUser_returnsDeserializedUser() {
        wireMock.stubFor(get(urlEqualTo("/users/42"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                    {"id": 42, "name": "Debopam"}
                """)));

        TestUser user = api.getUser(42L);

        assertThat(user.id()).isEqualTo(42L);
        assertThat(user.name()).isEqualTo("Debopam");
        wireMock.verify(getRequestedFor(urlEqualTo("/users/42")));
    }

    @Test
    void listUsers_sendsQueryParams() {
        wireMock.stubFor(get(urlPathEqualTo("/users"))
                .withQueryParam("page", equalTo("0"))
                .withQueryParam("size", equalTo("10"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                    [{"id": 1, "name": "Alice"}, {"id": 2, "name": "Bob"}]
                """)));

        List<TestUser> users = api.listUsers(0, 10);

        assertThat(users).hasSize(2);
        assertThat(users.get(0).name()).isEqualTo("Alice");
    }

    @Test
    void createUser_sendsJsonBody() {
        wireMock.stubFor(post(urlEqualTo("/users"))
                .withRequestBody(matchingJsonPath("$.name", equalTo("Charlie")))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                    {"id": 99, "name": "Charlie"}
                """)));

        TestUser created = api.createUser(new TestUser(0, "Charlie"));

        assertThat(created.id()).isEqualTo(99L);
    }

    @Test
    void apiException_thrownOn404() {
        wireMock.stubFor(get(urlEqualTo("/users/999"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withBody("Not Found")));

        assertThatThrownBy(() -> api.getUser(999L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatusCode()).isEqualTo(404);
                    assertThat(apiEx.isClientError()).isTrue();
                });
    }

    @Test
    void deleteUser_sends204WithNoBody() {
        wireMock.stubFor(delete(urlEqualTo("/users/5"))
                .willReturn(aResponse().withStatus(204)));

        assertThatCode(() -> api.deleteUser(5L)).doesNotThrowAnyException();
        wireMock.verify(deleteRequestedFor(urlEqualTo("/users/5")));
    }
}
