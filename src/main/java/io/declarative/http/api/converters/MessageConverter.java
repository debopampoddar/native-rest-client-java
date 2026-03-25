package io.declarative.http.api.converters;

import java.io.InputStream;
import java.lang.reflect.Type;

/**
 * An interface for serializing objects into request bodies and deserializing response bodies into objects.
 * This abstraction allows for plugging in different serialization libraries (e.g., Jackson, Gson).
 *
 * @author Debopam
 */
public interface MessageConverter {
    /**
     * Serializes an object into a byte array.
     *
     * @param object the object to serialize
     * @return the serialized byte array
     * @throws Exception if serialization fails
     */
    byte[] serialize(Object object) throws Exception;

    /**
     * Deserializes an {@link InputStream} into an object of the specified class.
     * <p>
     * Deserializing directly from the network stream is crucial for performance and memory efficiency,
     * as it avoids loading large payloads into memory all at once.
     *
     * @param stream the input stream to read from
     * @param type   the class of the target object
     * @param <T>    the type of the target object
     * @return the deserialized object
     * @throws Exception if deserialization fails
     */
    <T> T deserialize(InputStream stream, Class<T> type) throws Exception;

    /**
     * Deserializes an {@link InputStream} into an object of the specified generic type.
     *
     * @param stream the input stream to read from
     * @param type   the generic type of the target object
     * @param <T>    the type of the target object
     * @return the deserialized object
     * @throws Exception if deserialization fails
     */
    <T> T deserialize(InputStream stream, Type type) throws Exception;
}
