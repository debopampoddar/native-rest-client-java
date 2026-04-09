package io.declarative.http.api.converters;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.declarative.http.error.RestClientException;

import java.io.IOException;
import java.io.InputStream;

/**
 * A {@link ResponseConverter} implementation that uses the Jackson library
 * for JSON serialization and deserialization.
 *
 * @author Debopam
 */
public class JacksonConverter implements ResponseConverter {
    private static final ObjectMapper DEFAULT_MAPPER
            = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .findAndRegisterModules()
            .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final ObjectMapper objectMapper;

    /**
     * Creates a new converter with a default, pre-configured {@link ObjectMapper}.
     */
    public JacksonConverter() {
        this(DEFAULT_MAPPER);
    }

    /**
     * Creates a new converter with a custom {@link ObjectMapper}.
     *
     * @param mapper the custom mapper to use
     */
    public JacksonConverter(ObjectMapper mapper) {
        this.objectMapper = mapper;
    }

    @Override
    public <T> T convert(InputStream stream, JavaType javaType) {
        try {
            return objectMapper.readValue(stream, javaType);
        } catch (IOException e) {
            throw new RestClientException("JSON deserialization failed for type: " + javaType, e);
        }
    }

    @Override
    public boolean supports(String contentType) {
        return contentType != null &&
                (contentType.contains("application/json") ||
                        contentType.contains("text/json"));
    }
}
