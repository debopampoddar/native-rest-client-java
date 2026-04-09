package io.declarative.http.api.converters;

import com.fasterxml.jackson.databind.JavaType;
import io.declarative.http.error.RestClientException;


import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class StringConverter implements ResponseConverter {

    @Override
    @SuppressWarnings("unchecked")
    public <T> T convert(InputStream stream, JavaType javaType) {
        try {
            return (T) new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RestClientException("String conversion failed", e);
        }
    }

    @Override
    public boolean supports(String contentType) {
        return contentType != null && contentType.startsWith("text/plain");
    }
}
