package io.declarative.http.api.converters;

import com.fasterxml.jackson.databind.JavaType;
import java.io.InputStream;

/**
 * Plug-in SPI for response body deserialization.
 * Implement this to support Gson, Moshi, XML, Protobuf, etc.
 */
public interface ResponseConverter {

    /**
     * @param stream   raw response body stream (caller closes it)
     * @param javaType target deserialization type (constructed from method return type)
     * @param <T>      expected return type
     * @return deserialized response object
     */
    <T> T convert(InputStream stream, JavaType javaType);

    /**
     * @return true if this converter can handle the given content type
     */
    boolean supports(String contentType);
}
