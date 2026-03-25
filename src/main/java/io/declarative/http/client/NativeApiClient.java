package io.declarative.http.client;

import io.declarative.http.api.annotation.Body;
import io.declarative.http.api.annotation.DELETE;
import io.declarative.http.api.annotation.GET;
import io.declarative.http.api.annotation.Header;
import io.declarative.http.api.annotation.Headers;
import io.declarative.http.api.annotation.POST;
import io.declarative.http.api.annotation.PUT;
import io.declarative.http.api.annotation.Path;
import io.declarative.http.api.annotation.Query;
import io.declarative.http.api.annotation.Url;
import io.declarative.http.api.converters.JacksonConverter;
import io.declarative.http.api.converters.MessageConverter;
import io.declarative.http.api.interceptors.Interceptor;

import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * A declarative REST client based on Java's native {@link HttpClient}.
 * <p>
 * This client allows you to define a Java interface with annotated methods,
 * which are then translated into HTTP requests. It supports synchronous and
 * asynchronous execution, request/response interception, and pluggable JSON
 * serialization.
 *
 * @author Debopam
 */
public class NativeApiClient {

    private final HttpClient httpClient;
    private final Supplier<String> baseUrlSupplier;
    private final MessageConverter converter;
    private final List<Interceptor> interceptors;

    private NativeApiClient(Builder builder) {
        this.httpClient = builder.httpClient;
        this.baseUrlSupplier = builder.baseUrlSupplier;
        this.converter = builder.converter;
        this.interceptors = List.copyOf(builder.interceptors);
    }

    /**
     * Creates a dynamic proxy implementation of the provided API interface.
     *
     * @param apiInterface the interface class to implement
     * @param <T>          the type of the API interface
     * @return a proxy instance of the API interface
     */
    @SuppressWarnings("unchecked")
    public <T> T createService(Class<T> apiInterface) {
        if (!apiInterface.isInterface()) {
            throw new IllegalArgumentException("API declarations must be interfaces.");
        }
        return (T) Proxy.newProxyInstance(
                apiInterface.getClassLoader(),
                new Class<?>[]{apiInterface},
                new ApiInvocationHandler()
        );
    }

    /**
     * The core invocation handler that translates method calls into HTTP requests.
     */
    private class ApiInvocationHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            boolean isAsync = method.getReturnType().equals(CompletableFuture.class);
            Class<?> targetClass = isAsync
                    ? (Class<?>) ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments()[0]
                    : method.getReturnType();

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
            String endpoint = "";
            String httpMethod = "GET";
            String dynamicUrlOverride = null;
            StringBuilder queryBuilder = new StringBuilder();
            HttpRequest.BodyPublisher bodyPublisher = HttpRequest.BodyPublishers.noBody();

            // Determine HTTP Method and Endpoint Path
            if (method.isAnnotationPresent(GET.class)) {
                endpoint = method.getAnnotation(GET.class).value();
                httpMethod = "GET";
            } else if (method.isAnnotationPresent(POST.class)) {
                endpoint = method.getAnnotation(POST.class).value();
                httpMethod = "POST";
            } else if (method.isAnnotationPresent(PUT.class)) {
                endpoint = method.getAnnotation(PUT.class).value();
                httpMethod = "PUT";
            } else if (method.isAnnotationPresent(DELETE.class)) {
                endpoint = method.getAnnotation(DELETE.class).value();
                httpMethod = "DELETE";
            } else {
                throw new UnsupportedOperationException("HTTP Method annotation missing on " + method.getName());
            }

            //Process Static @Headers
            if (method.isAnnotationPresent(Headers.class)) {
                for (String header : method.getAnnotation(Headers.class).value()) {
                    String[] parts = header.split(":", 2);
                    if (parts.length == 2) requestBuilder.header(parts[0].trim(), parts[1].trim());
                }
            }

            // Process Parameters (@Url, @Header, @Path, @Body, @Query)
            if (args != null) {
                Parameter[] parameters = method.getParameters();
                for (int i = 0; i < parameters.length; i++) {
                    Object arg = args[i];
                    if (arg == null) continue;
                    if (parameters[i].isAnnotationPresent(Url.class)) {
                        dynamicUrlOverride = arg.toString(); // Captures the @Url override
                    } else if (parameters[i].isAnnotationPresent(Header.class)) {
                        requestBuilder.header(parameters[i].getAnnotation(Header.class).value(), arg.toString());
                    } else if (parameters[i].isAnnotationPresent(Path.class)) {
                        String pathName = parameters[i].getAnnotation(Path.class).value();
                        endpoint = endpoint.replace("{" + pathName + "}", arg.toString());
                    } else if (parameters[i].isAnnotationPresent(Query.class)) {
                        String queryName = parameters[i].getAnnotation(Query.class).value();
                        String queryValue = URLEncoder.encode(arg.toString(), StandardCharsets.UTF_8);
                        queryBuilder.append(queryBuilder.length() == 0 ? "?" : "&")
                                .append(queryName).append("=")
                                .append(queryValue);
                    } else if (parameters[i].isAnnotationPresent(Body.class)) {
                        bodyPublisher = HttpRequest.BodyPublishers.ofByteArray(converter.serialize(arg));
                        requestBuilder.header("Content-Type", "application/json");
                    }
                }
            }

            //Resolve Final URI
            String finalUriStr = (dynamicUrlOverride != null)
                    ? dynamicUrlOverride + queryBuilder.toString()
                    : baseUrlSupplier.get() + endpoint + queryBuilder.toString();
            // Build and Execute Request
            HttpRequest request = requestBuilder
                    .uri(URI.create(finalUriStr))
                    .method(httpMethod, bodyPublisher)
                    .build();

            // Pass through the interceptor chain
            HttpInterceptorChain chain = new HttpInterceptorChain(interceptors, 0, request, httpClient);
            CompletableFuture<HttpResponse<InputStream>> responseFuture = chain.proceed(request);

            // Map the response body to the target Object
            CompletableFuture<Object> resultFuture = responseFuture.thenApply(response -> {
                int status = response.statusCode();
                InputStream stream = response.body();

                try {
                    if (status >= 200 && status < 300) {
                        if (targetClass.equals(Void.TYPE)) return null;

                        // If the user wants the raw stream for large file downloads, give it to them immediately
                        if (targetClass.equals(InputStream.class)) return stream;

                        if (targetClass.equals(byte[].class)) return stream.readAllBytes();
                        if (targetClass.equals(String.class))
                            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);

                        // Otherwise, stream directly into the JSON parser
                        return converter.deserialize(stream, targetClass);
                    } else {
                        // Error handling
                        String errorBody = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                        throw new CompletionException(new RuntimeException("HTTP " + status + " - " + errorBody));
                    }
                } catch (Exception e) {
                    throw new CompletionException("Stream Processing Failed", e);
                }
            });

            if (isAsync) return resultFuture;
            try {
                return resultFuture.join();
            } catch (CompletionException e) {
                throw e.getCause();
            }
        }
    }

    /**
     * The internal implementation of the interceptor chain.
     */
    private static class HttpInterceptorChain implements Interceptor.Chain {
        private final List<Interceptor> interceptors;
        private final int index;
        private final HttpRequest request;
        private final HttpClient httpClient;

        HttpInterceptorChain(List<Interceptor> interceptors, int index, HttpRequest request, HttpClient httpClient) {
            this.interceptors = interceptors;
            this.index = index;
            this.request = request;
            this.httpClient = httpClient;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override public CompletableFuture<HttpResponse<InputStream>> proceed(HttpRequest request) {
            if (index < interceptors.size()) {
                return interceptors.get(index).intercept(new HttpInterceptorChain(interceptors, index + 1, request, httpClient));
            }
            // All responses are handled as InputStreams to prevent OutOfMemoryErrors on large payloads.
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        }
    }

    /**
     * A builder for creating instances of {@link NativeApiClient}.
     */
    public static class Builder {
        private HttpClient httpClient = HttpClient.newHttpClient();
        private Supplier<String> baseUrlSupplier;
        private MessageConverter converter = new JacksonConverter();
        private final List<Interceptor> interceptors = new ArrayList<>();

        /**
         * Sets the base URL for all API requests.
         *
         * @param baseUrl the base URL
         * @return this builder instance
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrlSupplier = () -> baseUrl;
            return this;
        }

        /**
         * Sets a custom {@link MessageConverter} for serializing and deserializing request/response bodies.
         * Defaults to {@link JacksonConverter}.
         *
         * @param converter the message converter
         * @return this builder instance
         */
        public Builder converter(MessageConverter converter) {
            this.converter = converter;
            return this;
        }

        /**
         * Sets the underlying {@link HttpClient} to use for requests.
         * Defaults to a new client created with {@code HttpClient.newHttpClient()}.
         *
         * @param httpClient the client to use
         * @return this builder instance
         */
        public Builder client(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /**
         * Adds an {@link Interceptor} to the request processing chain.
         *
         * @param interceptor the interceptor to add
         * @return this builder instance
         */
        public Builder addInterceptor(Interceptor interceptor) {
            this.interceptors.add(interceptor);
            return this;
        }

        /**
         * Builds and returns a new {@link NativeApiClient} instance.
         *
         * @return the constructed client
         * @throws IllegalStateException if a base URL has not been provided
         */
        public NativeApiClient build() {
            if (baseUrlSupplier == null) throw new IllegalStateException("Base URL required");
            return new NativeApiClient(this);
        }
    }
}
