package io.declarative.http.client;

import java.net.http.HttpHeaders;

public record HttpResponseEnvelope<T>(int status,
                                      HttpHeaders headers,
                                      T body) {
    public boolean isSuccessful() {
        return status >= 200 && status < 300;
    }
}
