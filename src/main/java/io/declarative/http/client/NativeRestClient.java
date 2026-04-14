package io.declarative.http.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.declarative.http.api.converters.JacksonConverter;
import io.declarative.http.api.converters.ResponseConverter;
import io.declarative.http.api.converters.StringConverter;
import io.declarative.http.api.interceptors.ClientInterceptor;
import io.declarative.http.api.interceptors.HttpExchangeInterceptor;

import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Primary entry point for the native declarative REST client library.
 *
 * <p>{@code NativeRestClient} turns annotated Java interfaces into fully functional,
 * type-safe HTTP clients backed by the JDK's built-in {@link java.net.http.HttpClient}.
 * At call time, a JDK dynamic proxy intercepts each method invocation, resolves its
 * metadata (HTTP verb, path, parameter bindings, return type) via {@link ResolvedMethod},
 * and delegates execution to {@link InvocationDispatcher}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // 1. Define an annotated interface
 * public interface UserApi {
 *     @GET("/users/{id}")
 *     User getUser(@Path("id") long id);
 *
 *     @POST("/users")
 *     User createUser(@Body User user);
 * }
 *
 * // 2. Build the client once (thread-safe; reuse across your application)
 * NativeRestClient client = NativeRestClient.builder("https://api.example.com")
 *         .addInterceptor(new BearerAuthInterceptor(tokenSupplier))
 *         .build();
 *
 * // 3. Create a typed proxy
 * UserApi userApi = client.create(UserApi.class);
 *
 * // 4. Call it like any ordinary Java object
 * User user = userApi.getUser(42L);
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>Instances of {@code NativeRestClient} are fully thread-safe. The internal
 * {@link ResolvedMethod} metadata is parsed lazily on first use and cached in a
 * {@link ConcurrentHashMap}, so subsequent calls to the same proxy method pay zero
 * reflection overhead.
 *
 * <h2>Design Notes</h2>
 * <ul>
 *   <li>Instances are created exclusively through the {@link Builder} — the constructor
 *       is intentionally private.</li>
 *   <li>{@code Object} methods ({@code equals}, {@code hashCode}, {@code toString})
 *       are short-circuited and never routed through the HTTP stack.</li>
 *   <li>Async methods whose return type is {@link java.util.concurrent.CompletableFuture}
 *       are dispatched non-blockingly via {@link HttpClient#sendAsync}.</li>
 * </ul>
 *
 * @see Builder
 * @see ResolvedMethod
 * @see InvocationDispatcher
 */
public final class NativeRestClient {

    /** Routes proxy method calls to the underlying {@link HttpClient}. */
    private final InvocationDispatcher dispatcher;

    /**
     * Cache of pre-parsed {@link ResolvedMethod} descriptors, keyed by the
     * reflected {@link Method} object. Written once per method on first invocation;
     * safe for concurrent access via {@link ConcurrentHashMap}.
     */
    private final Map<Method, ResolvedMethod> methodCache = new ConcurrentHashMap<>();

    /**
     * Private constructor — use {@link #builder(String)} to obtain an instance.
     *
     * @param dispatcher the fully configured dispatcher holding the HTTP client,
     *                   converters, and interceptor chains
     */
    private NativeRestClient(InvocationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * Creates a type-safe JDK dynamic proxy for the supplied annotated service interface.
     *
     * <p>The proxy class is created with the interface's own {@link ClassLoader} and
     * satisfies the interface contract. Each method invocation is intercepted and
     * routed through the following pipeline:
     * <ol>
     *   <li>Parse (or retrieve from cache) the {@link ResolvedMethod} descriptor.</li>
     *   <li>Build the {@link java.net.http.HttpRequest} via {@link RequestContext}.</li>
     *   <li>Execute {@link ClientInterceptor} chain (auth, custom headers, …).</li>
     *   <li>Execute {@link HttpExchangeInterceptor} chain around the actual HTTP send.</li>
     *   <li>Deserialise and return the response body.</li>
     * </ol>
     *
     * @param <T>     the service interface type
     * @param service the interface class to proxy; must be an interface and must
     *                declare at least one method
     * @return a proxy instance that implements {@code service}
     * @throws io.declarative.http.error.RestClientException if {@code service} is not
     *         an interface or declares no methods
     */
    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> service) {
        ResolvedMethod.validateInterface(service);

        return (T) java.lang.reflect.Proxy.newProxyInstance(
                service.getClassLoader(),
                new Class<?>[]{service},
                (proxy, method, args) -> {
                    // Short-circuit Object methods (equals, hashCode, toString)
                    if (method.getDeclaringClass() == Object.class) {
                        return method.invoke(this, args);
                    }
                    ResolvedMethod resolved = methodCache.computeIfAbsent(
                            method,
                            m -> ResolvedMethod.parse(m, dispatcher.objectMapper())
                    );
                    return dispatcher.dispatch(resolved, args);
                }
        );
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Returns a new {@link Builder} configured with the given base URL.
     *
     * <p>The base URL is the common prefix prepended to every path defined in the
     * service interface annotations (e.g. {@code /users/{id}}). A trailing slash,
     * if present, is automatically stripped to avoid double-slash URIs.
     *
     * @param baseUrl the root URL of the target API, e.g. {@code https://api.example.com/v1}
     * @return a new {@link Builder} instance
     * @throws NullPointerException if {@code baseUrl} is {@code null}
     */
    public static Builder builder(String baseUrl) {
        return new Builder(baseUrl);
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /**
     * Fluent builder for constructing a {@link NativeRestClient} instance.
     *
     * <p>All configuration is optional except the base URL passed to
     * {@link NativeRestClient#builder(String)}. Sensible defaults are provided
     * for the HTTP client (10-second connect timeout), Jackson {@link ObjectMapper}
     * (with {@link JavaTimeModule}), and response converters ({@link StringConverter}
     * first, {@link JacksonConverter} last).
     *
     * <h2>Example</h2>
     * <pre>{@code
     * NativeRestClient client = NativeRestClient.builder("https://api.example.com")
     *         .objectMapper(customMapper)
     *         .addInterceptor(new BearerAuthInterceptor(() -> tokenStore.current()))
     *         .addExchangeInterceptor(new RetryOnServerErrorInterceptor(3, 200))
     *         .build();
     * }</pre>
     */
    public static final class Builder {

        private final String baseUrl;
        private HttpClient httpClient;
        private ObjectMapper objectMapper;
        private final List<ClientInterceptor> interceptors = new ArrayList<>();
        private final List<ResponseConverter> converters = new ArrayList<>();
        /** Optional custom thread pool passed to the underlying {@link HttpClient}. */
        private Executor executor;
        private final List<HttpExchangeInterceptor> exchangeInterceptors = new ArrayList<>();

        /**
         * Private constructor — obtain via {@link NativeRestClient#builder(String)}.
         *
         * <p>Registers the {@link StringConverter} as the first converter in the chain.
         * {@link JacksonConverter} is appended last during {@link #build()}, once the
         * final {@link ObjectMapper} is resolved.
         *
         * @param baseUrl root URL; trailing slash is removed automatically
         * @throws NullPointerException if {@code baseUrl} is {@code null}
         */
        private Builder(String baseUrl) {
            Objects.requireNonNull(baseUrl, "baseUrl must not be null");
            this.baseUrl = baseUrl.endsWith("/")
                    ? baseUrl.substring(0, baseUrl.length() - 1)
                    : baseUrl;
            converters.add(new StringConverter());
        }

        /**
         * Supplies a custom {@link java.util.concurrent.Executor} to the underlying
         * {@link HttpClient}.
         *
         * <p>Use this to plug in a managed thread pool (e.g. a virtual-thread executor
         * in JDK 21+). If not set, {@link HttpClient} uses its own internal executor.
         *
         * @param executor the executor for async HTTP operations; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if {@code executor} is {@code null}
         */
        public Builder executor(Executor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /**
         * Overrides the default {@link HttpClient} with a pre-configured instance.
         *
         * <p>Useful when you need fine-grained control over TLS settings,
         * HTTP/2 preferences, redirect policies, or cookie handlers. When this
         * method is called, the {@link #executor(Executor)} setting is ignored
         * because the executor must be embedded inside the supplied client.
         *
         * @param httpClient the JDK HTTP client to use; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if {@code httpClient} is {@code null}
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
            return this;
        }

        /**
         * Supplies a custom {@link ObjectMapper} for JSON serialisation and
         * deserialisation.
         *
         * <p>If not called, the client creates a default mapper with
         * {@link JavaTimeModule} registered, enabling transparent handling of
         * {@link java.time.Instant}, {@link java.time.LocalDate}, and other
         * {@code java.time} types. Provide your own mapper when you need custom
         * serialisers, naming strategies, or additional modules (e.g. Kotlin, Joda).
         *
         * @param objectMapper the custom Jackson mapper; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if {@code objectMapper} is {@code null}
         */
        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
            return this;
        }

        /**
         * Appends a {@link ClientInterceptor} to the request-only interceptor chain.
         *
         * <p>Request interceptors run <em>before</em> the HTTP exchange and can inspect
         * or modify the outgoing {@link java.net.http.HttpRequest} (e.g. inject auth
         * headers, add correlation IDs). Interceptors are executed in the order they
         * are registered.
         *
         * @param interceptor the interceptor to add; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if {@code interceptor} is {@code null}
         * @see io.declarative.http.api.auth.BasicAuthInterceptor
         * @see io.declarative.http.api.auth.BearerAuthInterceptor
         * @see io.declarative.http.api.interceptors.LoggingInterceptor
         */
        public Builder addInterceptor(ClientInterceptor interceptor) {
            interceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
            return this;
        }

        /**
         * Appends an additional {@link ResponseConverter} to the converter chain.
         *
         * <p>Converters are consulted in registration order. The first converter
         * whose {@link ResponseConverter#canConvert} returns {@code true} is used.
         * {@link JacksonConverter} is always appended last during {@link #build()},
         * serving as the catch-all JSON converter.
         *
         * <p>Register custom converters here to support non-JSON formats such as
         * XML or Protocol Buffers.
         *
         * @param converter the converter to add; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if {@code converter} is {@code null}
         */
        public Builder addConverter(ResponseConverter converter) {
            converters.add(Objects.requireNonNull(converter, "converter"));
            return this;
        }

        /**
         * Appends an {@link HttpExchangeInterceptor} to the full-exchange interceptor chain.
         *
         * <p>Exchange interceptors wrap the entire HTTP send/receive cycle and can
         * observe both the request and the response. They are ideal for cross-cutting
         * concerns like metrics recording, response logging, retry on server errors,
         * and transparent token refresh.
         *
         * <p>Interceptors are invoked in reverse registration order (outermost first),
         * forming a classic chain-of-responsibility around the terminal HTTP call.
         *
         * @param interceptor the exchange interceptor to add; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if {@code interceptor} is {@code null}
         * @see io.declarative.http.api.interceptors.MetricsExchangeInterceptor
         * @see io.declarative.http.api.interceptors.RetryOnServerErrorInterceptor
         * @see io.declarative.http.api.interceptors.TokenRefreshExchangeInterceptor
         */
        public Builder addExchangeInterceptor(HttpExchangeInterceptor interceptor) {
            this.exchangeInterceptors.add(Objects.requireNonNull(interceptor));
            return this;
        }

        /**
         * Builds a default {@link HttpClient} when none was supplied via
         * {@link #httpClient(HttpClient)}.
         *
         * <p>The default client uses a 10-second connect timeout and, if an
         * {@link Executor} was provided via {@link #executor(Executor)}, plugs it
         * in as the async executor.
         *
         * @return a newly constructed {@link HttpClient}
         */
        private HttpClient buildDefaultClient() {
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10));
            if (executor != null) {
                builder.executor(executor);
            }
            return builder.build();
        }

        /**
         * Constructs and returns the fully configured {@link NativeRestClient}.
         *
         * <p>Steps performed during {@code build()}:
         * <ol>
         *   <li>Resolve the {@link ObjectMapper} — use the custom one if supplied,
         *       otherwise create a default with {@link JavaTimeModule}.</li>
         *   <li>Resolve the {@link HttpClient} — use the custom one if supplied,
         *       otherwise call {@link #buildDefaultClient()}.</li>
         *   <li>Append {@link JacksonConverter} as the last converter in the chain.</li>
         *   <li>Construct the {@link InvocationDispatcher} with all resolved
         *       components.</li>
         * </ol>
         *
         * @return a ready-to-use {@link NativeRestClient}
         */
        public NativeRestClient build() {
            ObjectMapper om = (objectMapper != null)
                    ? objectMapper
                    : new ObjectMapper().registerModule(new JavaTimeModule());

            HttpClient client = (httpClient != null)
                    ? httpClient
                    : buildDefaultClient();

            List<ResponseConverter> finalConverters = new ArrayList<>(converters);
            finalConverters.add(new JacksonConverter(om));

            InvocationDispatcher dispatcher = new InvocationDispatcher(
                    client, baseUrl, om, interceptors, finalConverters, exchangeInterceptors);

            return new NativeRestClient(dispatcher);
        }
    }
}
