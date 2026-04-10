package io.declarative.http.api.converters;

import com.fasterxml.jackson.databind.JavaType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Converter that reads the entire response body as a UTF-8 {@link String}.
 *
 * <p>Handles any method whose declared return type is exactly {@code String}
 * (or {@code CompletableFuture<String>} for async methods — the generic type
 * is unwrapped by {@code InvocationDispatcher} before {@link #canConvert} is called).
 *
 * <p>No JSON parsing is performed; the raw body bytes are returned as-is.
 * This is useful for:
 * <ul>
 *   <li>Plain-text endpoints ({@code text/plain}, {@code text/html})</li>
 *   <li>Raw JSON inspection without deserialisation</li>
 *   <li>Endpoints that return non-JSON content types</li>
 * </ul>
 *
 * <p>This converter is registered first in the chain so it takes priority
 * over {@link JacksonConverter} for {@code String} return types.
 *
 * <h3>Example</h3>
 * <pre>{@code
 * public interface HealthApi {
 *
 *     // Returns raw response body as a String
 *     @GET("/health")
 *     String checkHealth();
 *
 *     // Async variant — also handled by StringConverter
 *     @GET("/version")
 *     CompletableFuture<String> getVersion();
 * }
 * }</pre>
 */
public final class StringConverter implements ResponseConverter {

    /**
     * Returns {@code true} only when the target type is exactly {@link String}.
     */
    @Override
    public boolean canConvert(JavaType type) {
        return type.getRawClass() == String.class;
    }

    /**
     * Reads all bytes from {@code body} and decodes them as UTF-8.
     *
     * @param body the raw response {@link InputStream} (non-null, open)
     * @param type the target type (always {@code String} when this method is called)
     * @return the full response body as a UTF-8 {@link String}
     * @throws IOException if reading the stream fails
     */
    @Override
    public Object convert(InputStream body, JavaType type) throws IOException {
        return new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }
}
