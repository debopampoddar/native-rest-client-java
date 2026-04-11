package io.declarative.http.api.converters;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Converter that deserialises JSON response bodies using Jackson's
 * {@link ObjectMapper}.
 *
 * <p>Handles all return types <em>except</em>:
 * <ul>
 *   <li>{@link String} — handled by the higher-priority {@link StringConverter}</li>
 *   <li>{@link InputStream} — passed through directly by {@code InvocationDispatcher}
 *       before the converter chain is consulted</li>
 *   <li>{@code void} / {@code Void} — short-circuited in {@code InvocationDispatcher}
 *       for 204 responses</li>
 * </ul>
 *
 * <p>The {@link ObjectMapper} instance is injected at construction time and is the
 * same shared instance configured on {@code NativeRestClient.Builder}. It is
 * <em>not</em> created per-request.
 *
 * <h3>Supported return types (examples)</h3>
 * <pre>{@code
 * public interface UserApi {
 *
 *     @GET("/users/{id}")
 *     User getUser(@Path("id") long id);                    // POJO
 *
 *     @GET("/users")
 *     List<User> listUsers();                               // generic List
 *
 *     @GET("/config")
 *     Map<String, Object> getConfig();                      // generic Map
 *
 *     @GET("/users/{id}")
 *     CompletableFuture<User> getUserAsync(@Path("id") long id); // async POJO
 *
 *     @GET("/events")
 *     CompletableFuture<List<Event>> streamEvents();        // async generic List
 * }
 * }</pre>
 *
 * <h3>Custom ObjectMapper example</h3>
 * <pre>{@code
 * ObjectMapper mapper = new ObjectMapper()
 *     .registerModule(new JavaTimeModule())
 *     .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
 *
 * NativeRestClient client = NativeRestClient.builder("https://api.example.com")
 *     .objectMapper(mapper)
 *     .build();
 * }</pre>
 */
public final class JacksonConverter implements ResponseConverter {

    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper the shared Jackson mapper; must not be {@code null}
     */
    public JacksonConverter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper,
                "objectMapper must not be null");
    }

    /**
     * Returns {@code true} for all types except {@link String} and
     * {@link InputStream}, which are handled by other mechanisms.
     *
     * <p>Jackson can intrinsically handle all other types — POJOs, collections,
     * maps, enums, primitives, records, and sealed classes — using type
     * information embedded in the {@link JavaType}.
     */
    @Override
    public boolean canConvert(JavaType type) {
        Class<?> raw = type.getRawClass();
        return raw != String.class && raw != InputStream.class;
    }

    /**
     * Deserializes the JSON response body into the specified Java type.
     *
     * <p>Delegates directly to {@link ObjectMapper#readValue(InputStream, JavaType)},
     * which correctly handles:
     * <ul>
     *   <li>Generic types ({@code List<User>}, {@code Map<String, Long>})</li>
     *   <li>Parameterised types ({@code Page<Item>})</li>
     *   <li>Nested generics ({@code Map<String, List<Event>>})</li>
     * </ul>
     *
     * @param body the raw JSON response {@link InputStream} (non-null, open)
     * @param type the fully-resolved target {@link JavaType}
     * @return the deserialised Java object
     * @throws IOException if the JSON is malformed or the type mapping fails
     */
    @Override
    public Object convert(InputStream body, JavaType type) throws IOException {
        return objectMapper.readValue(body, type);
    }
}
