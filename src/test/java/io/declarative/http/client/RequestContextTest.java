package io.declarative.http.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.declarative.http.error.RestClientException;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestContextTest {

    private static RequestContext newContext(String pathTemplate) {
        return new RequestContext("GET", "http://localhost", pathTemplate, new ObjectMapper());
    }

    @Test
    void buildRequest_throwsWhenPathVariablesNotResolved() {
        RequestContext ctx = newContext("/users/{id}");
        // no replacePath call

        assertThatThrownBy(ctx::buildRequest)
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("Unresolved path variable");
    }

    @Test
    void addQueryParam_blankName_throwsRestClientException() {
        RequestContext ctx = newContext("/users");
        assertThatThrownBy(() -> ctx.addQueryParam(" ", "value"))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("Query parameter name must not be null or blank");
    }

    @Test
    void addHeader_blankName_throwsRestClientException() {
        RequestContext ctx = newContext("/users");
        assertThatThrownBy(() -> ctx.addHeader("", "v"))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("Header name must not be null or blank");
    }

    @Test
    void addFormField_blankName_throwsRestClientException() {
        RequestContext ctx = newContext("/login");
        assertThatThrownBy(() -> ctx.addFormField(" ", "v"))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("Form field name must not be null or blank");
    }

    @Test
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
}
