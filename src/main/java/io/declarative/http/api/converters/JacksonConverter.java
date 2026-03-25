package io.declarative.http.api.converters;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.lang.reflect.Type;

/**
 * A {@link MessageConverter} implementation that uses the Jackson library
 * for JSON serialization and deserialization.
 *
 * @author Debopam
 */
public class JacksonConverter implements MessageConverter {
    private static final ObjectMapper DEFAULT_MAPPER
            = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .findAndRegisterModules()
            .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final ObjectMapper mapper;

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
        this.mapper = mapper;
    }

    @Override
    public byte[] serialize(Object object) throws Exception {
        return mapper.writeValueAsBytes(object);
    }

    @Override
    public <T> T deserialize(InputStream stream, Class<T> type) throws Exception {
        if (stream == null) return null;
        return mapper.readValue(stream, mapper.constructType(type));
    }

    @Override
    public <T> T deserialize(InputStream stream, Type type) throws Exception {
        if (stream == null) return null;
        return mapper.readValue(stream, mapper.constructType(type));
    }
}
