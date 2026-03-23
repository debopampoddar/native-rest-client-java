package io.declarative.http.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.declarative.http.api.Body;
import io.declarative.http.api.GET;
import io.declarative.http.api.POST;
import io.declarative.http.api.Path;
import io.declarative.http.api.interceptors.Interceptor;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * A declarative REST client based on Java's native {@link HttpClient}.
 * It creates implementations of interfaces dynamically using dynamic proxies,
 * similar to Retrofit or Feign.
 *
 * @author Debopam
 */
public class NativeApiClient {

    private final HttpClient httpClient;
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final List<Interceptor> interceptors;

    private NativeApiClient(Builder builder) {
        this.httpClient = builder.httpClient;
        this.baseUrl = builder.baseUrl;
        this.objectMapper = builder.objectMapper;
        this.interceptors = List.copyOf(builder.interceptors);
    }

    /**
     * Creates an implementation of the API endpoints defined by the given interface.
     *
     * @param service the interface class defining the API
     * @param <T>     the type of the interface
     * @return a proxy object implementing the service interface
     */
    @SuppressWarnings("unchecked")
    public <T> T createService(Class<T> service) {
        return (T) Proxy.newProxyInstance(
                service.getClassLoader(),
                new Class<?>[]{service},
                new ApiInvocationHandler()
        );
    }

    /**
     * An invocation handler that intercepts method calls on the proxy and converts them
     * into HTTP requests using the configured native HttpClient.
     */
    private class ApiInvocationHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
            String endpoint = "";
            String httpMethod = "GET";
            HttpRequest.BodyPublisher bodyPublisher = HttpRequest.BodyPublishers.noBody();

            // Process Method Annotations (GET, POST)
            if (method.isAnnotationPresent(GET.class)) {
                endpoint = method.getAnnotation(GET.class).value();
            } else if (method.isAnnotationPresent(POST.class)) {
                httpMethod = "POST";
                endpoint = method.getAnnotation(POST.class).value();
            }

            // Process Parameters (@Path, @Body)
            Parameter[] parameters = method.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                if (parameters[i].isAnnotationPresent(Path.class)) {
                    String pathName = parameters[i].getAnnotation(Path.class).value();
                    endpoint = endpoint.replace("{" + pathName + "}", args[i].toString());
                } else if (parameters[i].isAnnotationPresent(Body.class)) {
                    String jsonBody = objectMapper.writeValueAsString(args[i]);
                    bodyPublisher = HttpRequest.BodyPublishers.ofString(jsonBody);
                    requestBuilder.header("Content-Type", "application/json");
                }
            }

            // Build and Execute Request
            HttpRequest request = requestBuilder
                    .uri(URI.create(baseUrl + endpoint))
                    .method(httpMethod, bodyPublisher)
                    .header("Content-Type", "application/json")
                    .build();

            // Pass through the interceptor chain
            RealInterceptorChain chain = new RealInterceptorChain(interceptors, 0, request, httpClient);
            CompletableFuture<HttpResponse<String>> responseFuture = chain.proceed(request);

            // Determine Return Type
            boolean returnsFuture = method.getReturnType().equals(CompletableFuture.class);
            Class<?> targetClass;

            if (returnsFuture) {
                // Extract 'T' from CompletableFuture<T>
                Type genericReturnType = method.getGenericReturnType();
                if (genericReturnType instanceof ParameterizedType) {
                    targetClass = (Class<?>) ((ParameterizedType) genericReturnType).getActualTypeArguments()[0];
                } else {
                    targetClass = String.class;
                }
            } else {
                targetClass = method.getReturnType();
            }

            // Map the response body to the target Object
            CompletableFuture<Object> resultFuture = responseFuture.thenApply(response -> {
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    if (targetClass.equals(String.class)) return response.body();
                    try {
                        return objectMapper.readValue(response.body(), targetClass);
                    } catch (Exception e) {
                        throw new CompletionException("Deserialization failed", e);
                    }
                } else {
                    throw new RuntimeException("API Call failed: " + response.statusCode());
                }
            });

            // Return Async or block for Sync
            return returnsFuture ? resultFuture : resultFuture.join();
        }
    }

    private static class RealInterceptorChain implements Interceptor.Chain {
        private final List<Interceptor> interceptors;
        private final int index;
        private final HttpRequest request;
        private final HttpClient httpClient;

        RealInterceptorChain(List<Interceptor> interceptors, int index, HttpRequest request, HttpClient httpClient) {
            this.interceptors = interceptors;
            this.index = index;
            this.request = request;
            this.httpClient = httpClient;
        }

        @Override
        public HttpRequest request() { return request; }

        @Override
        public CompletableFuture<HttpResponse<String>> proceed(HttpRequest request) {
            if (index < interceptors.size()) {
                Interceptor.Chain nextChain = new RealInterceptorChain(interceptors, index + 1, request, httpClient);
                return interceptors.get(index).intercept(nextChain);
            } else {
                // End of chain: Do the actual network IO
                return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            }
        }
    }

    /**
     * Builder for {@link NativeApiClient}.
     * Mimics the Retrofit Builder pattern to configure the client.
     */
    public static class Builder {
        private HttpClient httpClient = HttpClient.newHttpClient();
        private String baseUrl;
        private ObjectMapper objectMapper = new ObjectMapper();
        private final List<Interceptor> interceptors = new ArrayList<>();
        /**
         * Sets the base URL for the API.
         *
         * @param baseUrl the base URL
         * @return this builder instance
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Sets the {@link HttpClient} to be used by the API client.
         *
         * @param client the HTTP client
         * @return this builder instance
         */
        public Builder client(HttpClient client) {
            this.httpClient = client;
            return this;
        }

        public Builder addInterceptor(Interceptor interceptor) {
            this.interceptors.add(interceptor); return this;
        }

        /**
         * Builds and returns a new {@link NativeApiClient}.
         *
         * @return the constructed NativeApiClient
         * @throws IllegalStateException if a base URL has not been provided
         */
        public NativeApiClient build() {
            if (baseUrl == null) throw new IllegalStateException("Base URL required");
            return new NativeApiClient(this);
        }
    }
}