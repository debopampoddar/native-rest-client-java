package io.declarative.http.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.declarative.http.api.converters.JacksonConverter;
import io.declarative.http.api.converters.ResponseConverter;
import io.declarative.http.api.converters.StringConverter;
import io.declarative.http.api.interceptors.ClientInterceptor;
import io.declarative.http.error.RestClientException;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Entry point for the native REST client framework.
 *
 * Usage:
 * <pre>{@code
 *   NativeRestClient client = NativeRestClient.builder("https://api.example.com")
 *       .connectTimeout(Duration.ofSeconds(5))
 *       .addInterceptor(new LoggingInterceptor())
 *       .addInterceptor(new BearerAuthInterceptor(() -> tokenProvider.get()))
 *       .build();
 *
 *   UserApi api = client.create(UserApi.class);
 *   User user = api.getUser(42L);
 * }</pre>
 */
public final class NativeRestClient {

    // Shared HttpClient — thread-safe, pooled, created once
    private final HttpClient httpClient;
    private final InvocationDispatcher dispatcher;

    // Method cache: pre-computed metadata per Method, populated at create() time
    private final Map<Method, ResolvedMethod> methodCache = new ConcurrentHashMap<>();

    private NativeRestClient(Builder builder) {
        ObjectMapper objectMapper = builder.objectMapper;

        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(builder.connectTimeout)
                .executor(builder.executor.get())   // Java 21 virtual thread executor
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        List<ResponseConverter> converters = new ArrayList<>(builder.converters);
        // Register defaults if none supplied
        if (converters.isEmpty()) {
            converters.add(new JacksonConverter(objectMapper));
            converters.add(new StringConverter());
        }

        this.dispatcher = new InvocationDispatcher(
                httpClient, builder.baseUrl, objectMapper,
                builder.interceptors, converters);
    }

    /**
     * Creates a proxy implementation of the given service interface.
     * All annotation metadata is pre-computed and cached here.
     *
     * @param service the annotated interface class
     * @param <T>     service type
     * @return thread-safe proxy ready for concurrent use
     */
    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> service) {
        validateInterface(service);

        // Pre-warm the cache for all methods at creation time (not at call time)
        Arrays.stream(service.getDeclaredMethods())
                .filter(m -> !m.isDefault())
                .forEach(m -> methodCache.computeIfAbsent(m,
                        method -> ResolvedMethod.parse(method,
                                // share the ObjectMapper from dispatcher
                                new ObjectMapper()
                                        .registerModule(new JavaTimeModule())
                                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS))));

        return (T) Proxy.newProxyInstance(
                service.getClassLoader(),
                new Class<?>[]{service},
                (proxy, method, args) -> {
                    // Delegate Object methods without HTTP dispatch
                    if (method.getDeclaringClass() == Object.class) {
                        return method.invoke(this, args);
                    }
                    // Delegate default interface methods without HTTP dispatch
                    if (method.isDefault()) {
                        return method.invoke(proxy, args);
                    }
                    ResolvedMethod resolved = methodCache.get(method);
                    if (resolved == null) {
                        throw new RestClientException(
                                "Method not registered: " + method.getName() +
                                        ". This is an internal error — please file a bug.");
                    }
                    return dispatcher.dispatch(resolved, args);
                }
        );
    }

    private void validateInterface(Class<?> service) {
        if (!service.isInterface()) {
            throw new RestClientException(
                    service.getName() + " must be an interface, not a class or abstract type.");
        }
        if (service.getMethods().length == 0) {
            throw new RestClientException(
                    service.getName() + " has no declared methods.");
        }
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static Builder builder(String baseUrl) {
        return new Builder(baseUrl);
    }

    public static final class Builder {

        private final String baseUrl;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Supplier<java.util.concurrent.Executor> executor =
                Executors::newVirtualThreadPerTaskExecutor; // Java 21 virtual threads
        private ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        private final List<ClientInterceptor> interceptors = new ArrayList<>();
        private final List<ResponseConverter> converters = new ArrayList<>();

        private Builder(String baseUrl) {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("baseUrl must not be null or blank");
            }
            this.baseUrl = baseUrl.endsWith("/")
                    ? baseUrl.substring(0, baseUrl.length() - 1)
                    : baseUrl;
        }

        public Builder connectTimeout(Duration timeout) {
            this.connectTimeout = timeout;
            return this;
        }

        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        /**
         * Adds a request interceptor. Interceptors are applied in registration order.
         * Common uses: logging, auth token injection, retry, circuit breaking.
         */
        public Builder addInterceptor(ClientInterceptor interceptor) {
            this.interceptors.add(interceptor);
            return this;
        }

        /**
         * Adds a custom response converter. Evaluated before built-in converters.
         */
        public Builder addConverter(ResponseConverter converter) {
            this.converters.add(0, converter); // user converters take priority
            return this;
        }

        public NativeRestClient build() {
            return new NativeRestClient(this);
        }
    }
}
