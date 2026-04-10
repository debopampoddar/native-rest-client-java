package io.declarative.http.api.converters;

import com.fasterxml.jackson.databind.JavaType;

import java.io.IOException;
import java.io.InputStream;

/**
 * SPI (Service Provider Interface) for converting a raw HTTP response body
 * into the method's declared Java return type.
 *
 * <p>The converter chain is tried in registration order inside
 * {@code InvocationDispatcher}. The first converter that returns {@code true}
 * from {@link #canConvert(JavaType)} is used exclusively for that response.
 *
 * <p>Built-in converters registered by default (in priority order):
 * <ol>
 *   <li>{@link StringConverter}  — handles {@code String} return types</li>
 *   <li>{@link JacksonConverter} — handles everything else via Jackson</li>
 * </ol>
 *
 * <p>Custom converters can be added to the chain via
 * {@code NativeRestClient.Builder} to support XML, Protobuf, CSV, etc.
 */
public interface ResponseConverter {

    /**
     * Returns {@code true} if this converter is capable of deserialising
     * a response body into the given Java type.
     *
     * <p>This method must be side-effect free and inexpensive — it is called
     * on every request dispatch.
     *
     * @param type the fully-resolved {@link JavaType} of the method return type
     *             (unwrapped from {@code CompletableFuture<T>} for async methods)
     * @return {@code true} if {@link #convert(InputStream, JavaType)} can handle
     *         this type; {@code false} to pass to the next converter in the chain
     */
    boolean canConvert(JavaType type);

    /**
     * Deserialises the response body {@link InputStream} into the target type.
     *
     * <p>Implementations are responsible for closing any resources they open
     * internally. The caller ({@code InvocationDispatcher}) closes the top-level
     * {@code InputStream} in a try-with-resources block after this method returns.
     *
     * <p>This method is only called when {@link #canConvert(JavaType)} returned
     * {@code true} for the same {@code type}.
     *
     * @param body the raw response body as a non-null, open {@link InputStream}
     * @param type the target Java type to deserialise into
     * @return the deserialised object; never {@code null} unless the type itself
     *         is nullable (e.g. {@code Optional<T>})
     * @throws IOException if the body cannot be read or parsed
     */
    Object convert(InputStream body, JavaType type) throws IOException;
}
