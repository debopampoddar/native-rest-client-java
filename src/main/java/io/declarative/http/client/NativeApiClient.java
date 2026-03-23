package io.declarative.http.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.declarative.http.api.Body;
import io.declarative.http.api.GET;
import io.declarative.http.api.POST;
import io.declarative.http.api.Path;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.Supplier;

public class NativeApiClient {

    private final HttpClient httpClient;
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final Supplier<String> tokenSupplier; // For dynamic Bearer Auth

    private NativeApiClient(Builder builder) {
        this.httpClient = builder.httpClient;
        this.baseUrl = builder.baseUrl;
        this.objectMapper = builder.objectMapper;
        this.tokenSupplier = builder.tokenSupplier;
    }

    @SuppressWarnings("unchecked")
    public <T> T createService(Class<T> service) {
        return (T) Proxy.newProxyInstance(
                service.getClassLoader(),
                new Class<?>[]{service},
                new ApiInvocationHandler()
        );
    }

    private class ApiInvocationHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
            String endpoint = "";
            String httpMethod = "GET";
            HttpRequest.BodyPublisher bodyPublisher = HttpRequest.BodyPublishers.noBody();

            // 1. Process Method Annotations (GET, POST)
            if (method.isAnnotationPresent(GET.class)) {
                endpoint = method.getAnnotation(GET.class).value();
            } else if (method.isAnnotationPresent(POST.class)) {
                httpMethod = "POST";
                endpoint = method.getAnnotation(POST.class).value();
            }

            // 2. Process Parameters (@Path, @Body)
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

            // 3. Handle Authentication (Bearer Token)
            if (tokenSupplier != null && tokenSupplier.get() != null) {
                requestBuilder.header("Authorization", "Bearer " + tokenSupplier.get());
            }

            // 4. Build and Execute Request
            HttpRequest request = requestBuilder
                    .uri(URI.create(baseUrl + endpoint))
                    .method(httpMethod, bodyPublisher)
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // 5. Handle Response & Deserialization
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                // If the return type is String, return raw body. Otherwise, deserialize.
                if (method.getReturnType().equals(String.class)) {
                    return response.body();
                }
                return objectMapper.readValue(response.body(), method.getReturnType());
            } else {
                throw new RuntimeException("API Call failed with status: " + response.statusCode() + " Body: " + response.body());
            }
        }
    }

    // Builder Pattern to mimic Retrofit.Builder()
    public static class Builder {
        private HttpClient httpClient = HttpClient.newHttpClient();
        private String baseUrl;
        private ObjectMapper objectMapper = new ObjectMapper();
        private Supplier<String> tokenSupplier;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder client(HttpClient client) {
            this.httpClient = client;
            return this;
        }

        public Builder bearerAuth(Supplier<String> tokenSupplier) {
            this.tokenSupplier = tokenSupplier;
            return this;
        }

        public NativeApiClient build() {
            if (baseUrl == null) throw new IllegalStateException("Base URL required");
            return new NativeApiClient(this);
        }
    }
}