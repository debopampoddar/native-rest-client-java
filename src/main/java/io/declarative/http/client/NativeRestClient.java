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
 * Entry point: builds type-safe HTTP client proxies from annotated interfaces.
 */
public final class NativeRestClient {

    private final InvocationDispatcher dispatcher;
    private final Map<Method, ResolvedMethod> methodCache = new ConcurrentHashMap<>();

    private NativeRestClient(InvocationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * Creates a type-safe proxy for the given API interface.
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

    // ── Builder ───────────────────────────────────────────────────────────────

    public static Builder builder(String baseUrl) {
        return new Builder(baseUrl);
    }

    public static final class Builder {

        private final String baseUrl;
        private HttpClient httpClient;
        private ObjectMapper objectMapper;
        private final List<ClientInterceptor> interceptors = new ArrayList<>();
        private final List<ResponseConverter> converters = new ArrayList<>();
        private Executor executor; // optional
        private final List<HttpExchangeInterceptor> exchangeInterceptors = new ArrayList<>();

        public Builder executor(Executor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        private Builder(String baseUrl) {
            Objects.requireNonNull(baseUrl, "baseUrl must not be null");
            this.baseUrl = baseUrl.endsWith("/")
                    ? baseUrl.substring(0, baseUrl.length() - 1)
                    : baseUrl;

            // Default converters in priority order
            converters.add(new StringConverter());
            // JacksonConverter will be added in build() once we know the final ObjectMapper
        }

        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
            return this;
        }

        /**
         * Provides a custom ObjectMapper. If not set, a default one with
         * {@link JavaTimeModule} is created once and reused for all calls.
         */
        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
            return this;
        }

        public Builder addInterceptor(ClientInterceptor interceptor) {
            interceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
            return this;
        }

        /**
         * Registers an additional response converter (e.g. XML, Protobuf).
         * Converters are consulted in registration order before Jackson.
         */
        public Builder addConverter(ResponseConverter converter) {
            converters.add(Objects.requireNonNull(converter, "converter"));
            return this;
        }

        public Builder addExchangeInterceptor(HttpExchangeInterceptor interceptor) {
            this.exchangeInterceptors.add(Objects.requireNonNull(interceptor));
            return this;
        }

        private HttpClient buildDefaultClient() {
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10));
            if (executor != null) {
                builder.executor(executor);
            }
            return builder.build();
        }

        public NativeRestClient build() {
            ObjectMapper om = (objectMapper != null)
                    ? objectMapper
                    : new ObjectMapper().registerModule(new JavaTimeModule());

            HttpClient client = (httpClient != null)
                    ? httpClient
                    : buildDefaultClient();

            List<ResponseConverter> finalConverters = new ArrayList<>(converters);
            finalConverters.add(new JacksonConverter(om)); // last in chain

            InvocationDispatcher dispatcher = new InvocationDispatcher(
                    client, baseUrl, om, interceptors, finalConverters, exchangeInterceptors);

            return new NativeRestClient(dispatcher);
        }
    }
}
