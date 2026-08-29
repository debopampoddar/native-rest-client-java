package io.declarative.http.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.declarative.http.error.RestClientException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.net.http.HttpRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestContextTest {

    private static RequestContext newContext(String pathTemplate) {
        return new RequestContext("GET", "http://localhost", pathTemplate, new ObjectMapper());
    }

    @Test
    @DisplayName("Build request throws when path variables not resolved")
    void buildRequest_throwsWhenPathVariablesNotResolved() {
        RequestContext ctx = newContext("/users/{id}");
        // no replacePath call

        assertThatThrownBy(ctx::buildRequest)
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("Unresolved path variable");
    }

    @Test
    @DisplayName("Add query param blank name throws rest client exception")
    void addQueryParam_blankName_throwsRestClientException() {
        RequestContext ctx = newContext("/users");
        assertThatThrownBy(() -> ctx.addQueryParam(" ", "value"))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("Query parameter name must not be null or blank");
    }

    @Test
    @DisplayName("Add header blank name throws rest client exception")
    void addHeader_blankName_throwsRestClientException() {
        RequestContext ctx = newContext("/users");
        assertThatThrownBy(() -> ctx.addHeader("", "v"))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("Header name must not be null or blank");
    }

    @Test
    @DisplayName("Add form field blank name throws rest client exception")
    void addFormField_blankName_throwsRestClientException() {
        RequestContext ctx = newContext("/login");
        assertThatThrownBy(() -> ctx.addFormField(" ", "v"))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("Form field name must not be null or blank");
    }

    @Test
    @DisplayName("Form url encoded with fields builds request with encoded body and header")
    void formUrlEncoded_withFields_buildsRequestWithEncodedBodyAndHeader() {
        RequestContext ctx = new RequestContext(
                "POST", "http://localhost", "/login", new ObjectMapper());
        ctx.setFormUrlEncoded(true);
        ctx.addFormField("username", "alice");
        ctx.addFormField("password", "secret");

        HttpRequest request = ctx.buildRequest();

        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.headers().firstValue("Content-Type"))
                .contains("application/x-www-form-urlencoded");
        assertThat(request.uri().toString()).isEqualTo("http://localhost/login");
    }

    @Test
    @DisplayName("Json body string bypasses jackson and uses raw value")
    void jsonBody_stringBypassesJacksonAndUsesRawValue() {
        RequestContext ctx = new RequestContext(
                "POST", "http://localhost", "/echo", new ObjectMapper());
        ctx.setBody("{\"name\":\"Alice\"}");

        HttpRequest request = ctx.buildRequest();

        assertThat(request.method()).isEqualTo("POST");
        assertTrue(request.headers().firstValue("Content-Type").get()
                .contains("application/json"));
        // Body content itself is validated indirectly in NativeRestClient tests,
        // but this at least exercises the String body publisher branch.
    }

    @Test
    @DisplayName("Build request merges existing query before fragment")
    void buildRequest_mergesExistingQueryBeforeFragment() {
        RequestContext ctx = newContext("/search?existing=one#section");
        ctx.addQueryParam("added", "two%20words");

        assertThat(ctx.buildRequest().uri().toString())
                .isEqualTo("http://localhost/search?existing=one&added=two%20words#section");
    }

    @Test
    @DisplayName("Caller headers replace defaults case insensitively")
    void callerHeaders_replaceDefaultsCaseInsensitively() {
        RequestContext ctx = newContext("/items");
        ctx.addHeader("accept", "text/plain");

        assertThat(ctx.buildRequest().headers().allValues("Accept"))
                .containsExactly("text/plain");
    }
}
