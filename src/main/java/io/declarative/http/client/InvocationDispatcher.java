package io.declarative.http.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.declarative.http.api.converters.ResponseConverter;
import io.declarative.http.api.interceptors.ClientInterceptor;
import io.declarative.http.api.interceptors.InterceptorChain;
import io.declarative.http.error.ApiException;
import io.declarative.http.error.RestClientException;
import io.declarative.http.handler.ParameterHandler;
import io.declarative.http.security.HeaderSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Core execution engine: assembles the request, runs the interceptor chain,
 * dispatches to HttpClient, and deserializes the response.
 */
public final class InvocationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InvocationDispatcher.class);

    private final HttpClient httpClient;
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final List<ClientInterceptor> interceptors;
    private final List<ResponseConverter> converters;

    public InvocationDispatcher(HttpClient httpClient, String baseUrl,
                                ObjectMapper objectMapper,
                                List<ClientInterceptor> interceptors,
                                List<ResponseConverter> converters) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.interceptors = List.copyOf(interceptors);
        this.converters = List.copyOf(converters);
    }

    public Object dispatch(ResolvedMethod resolved, Object[] args) {
        HttpRequest request = buildRequest(resolved, args);

        if (resolved.isAsync()) {
            return executeAsync(request, resolved);
        } else {
            return executeSync(request, resolved);
        }
    }

    private HttpRequest buildRequest(ResolvedMethod resolved, Object[] args) {
        RequestContext ctx = new RequestContext(
                resolved.httpMethod(), baseUrl, resolved.pathTemplate(), objectMapper);

        List<ParameterHandler> handlers = resolved.handlers();
        for (int i = 0; i < handlers.size(); i++) {
            handlers.get(i).apply(ctx, args != null && i < args.length ? args[i] : null);
        }

        HttpRequest request = ctx.buildRequest();

        // Run interceptor chain synchronously before dispatch
        try {
            request = new InterceptorChain(interceptors).proceed(request);
        } catch (IOException e) {
            throw new RestClientException("Interceptor chain failed", e);
        }

        log.debug("→ {} {} headers={}",
                request.method(), request.uri(),
                HeaderSanitizer.sanitize(request.headers()));

        return request;
    }

    private <T> T executeSync(HttpRequest request, ResolvedMethod resolved) {
        try {
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return handleResponse(response, resolved);
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RestClientException("Request interrupted", e);
        } catch (Exception e) {
            throw new RestClientException("Request execution failed: " + e.getMessage(), e);
        }
    }

    private <T> CompletableFuture<T> executeAsync(HttpRequest request, ResolvedMethod resolved) {
        return httpClient
                .sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(response -> handleResponse(response, resolved));
    }

    @SuppressWarnings("unchecked")
    private <T> T handleResponse(HttpResponse<InputStream> response, ResolvedMethod resolved) {
        int status = response.statusCode();

        log.debug("← HTTP {} for {} {}",
                status, response.request().method(), response.request().uri());

        try (InputStream body = response.body()) {
            if (status >= 400) {
                String errorBody = new String(body.readAllBytes());
                log.warn("API error {}: {}", status, errorBody);
                throw new ApiException(status, errorBody);
            }

            // Void/null return type
            if (resolved.responseType().getRawClass() == Void.class ||
                    resolved.responseType().getRawClass() == void.class) {
                return null;
            }

            // Resolve appropriate converter from Content-Type
            String contentType = response.headers()
                    .firstValue("content-type").orElse("application/json");

            ResponseConverter converter = converters.stream()
                    .filter(c -> c.supports(contentType))
                    .findFirst()
                    .orElseThrow(() -> new RestClientException(
                            "No converter available for content-type: " + contentType));

            return (T) converter.convert(body, resolved.responseType());

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new RestClientException("Response processing failed", e);
        }
    }
}
