package io.declarative.http.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.declarative.http.api.Body;
import io.declarative.http.api.GET;
import io.declarative.http.api.POST;
import io.declarative.http.api.Path;
import io.declarative.http.api.auth.ApiInterceptor;
import io.declarative.http.api.interceptors.RequestExecutor;

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
    private final ApiInterceptor interceptor;

    private NativeApiClient(Builder builder) {
        this.httpClient = builder.httpClient;
        this.baseUrl = builder.baseUrl;
        this.objectMapper = builder.objectMapper;
        this.interceptor = builder.interceptor;
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

            // Setup the base executor to actually fire the HttpClient
            RequestExecutor baseExecutor = req ->
                    httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString());

            // Pass through the interceptor chain
            CompletableFuture<HttpResponse<String>> responseFuture =
                    interceptor.intercept(request, baseExecutor);

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

            // 6. Return Async or block for Sync
            return returnsFuture ? resultFuture : resultFuture.join();
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
        private ApiInterceptor interceptor = (req, chain) -> chain.execute(req);
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

        public Builder interceptor(ApiInterceptor interceptor) {
            this.interceptor = interceptor; return this;
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