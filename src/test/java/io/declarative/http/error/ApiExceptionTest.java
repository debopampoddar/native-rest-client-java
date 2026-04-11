package io.declarative.http.error;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionTest {

    @Test
    void clientErrorFlagsAreCorrect() {
        ApiException e = new ApiException(404, "Not found");
        assertThat(e.getStatusCode()).isEqualTo(404);
        assertThat(e.getResponseBody()).isEqualTo("Not found");
        assertThat(e.isClientError()).isTrue();
        assertThat(e.isServerError()).isFalse();
        assertThat(e).hasMessageContaining("HTTP 404: Not found");
    }

    @Test
    void serverErrorFlagsAreCorrect() {
        ApiException e = new ApiException(500, "Internal");
        assertThat(e.isClientError()).isFalse();
        assertThat(e.isServerError()).isTrue();
    }

    @Test
    void nonErrorStatusHasNoClientOrServerFlag() {
        ApiException e = new ApiException(200, "OK-ish");
        assertThat(e.isClientError()).isFalse();
        assertThat(e.isServerError()).isFalse();
    }
}
