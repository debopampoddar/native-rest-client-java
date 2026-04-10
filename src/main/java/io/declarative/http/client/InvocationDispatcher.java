package io.declarative.http.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.declarative.http.api.converters.JacksonConverter;
import io.declarative.http.api.converters.ResponseConverter;
import io.declarative.http.api.converters.StringConverter;
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
 * Routes proxy method invocations to java.net.http.HttpClient.
 *
 * <p>FIX (P1): HTTP call timing now correctly measures the real network round-trip.
 *              Previously the stopwatch wrapped {@code chain.proceed()} which only
 *              traverses the interceptor chain and returns a modified HttpRequest —
 *              it does NOT execute the HTTP call. The timer now surrounds
 *              {@code httpClient.send()} / {@code httpClient.sendAsync()}.
 */
public final class InvocationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InvocationDispatcher.class);

    private final HttpClient httpClient;
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final List<ClientInterceptor> interceptors;
    private final List<ResponseConverter> converters;

    public InvocationDispatcher(HttpClient httpClient,
                                String baseUrl,
                                ObjectMapper objectMapper,
                                List<ClientInterceptor> interceptors) {
        this.httpClient   = httpClient;
        this.baseUrl      = baseUrl;
        this.objectMapper = objectMapper;
        this.interceptors = List.copyOf(interceptors);
        this.converters   = List.of(new StringConverter(),
                new JacksonConverter(objectMapper));
    }

    public Object dispatch(ResolvedMethod resolved, Object[] args) {
        HttpRequest request = buildRequest(resolved, args);
        if (resolved.isAsync()) {
            return executeAsync(request, resolved);
        }
        return executeSync(request, resolved);
    }

    // ── Request building ──────────────────────────────────────────────────────

    private HttpRequest buildRequest(ResolvedMethod resolved, Object[] args) {
        RequestContext ctx = new RequestContext(
                resolved.httpMethod(), baseUrl, resolved.pathTemplate(), objectMapper);

        // Apply @FormUrlEncoded flag to context
        if (resolved.isFormUrlEncoded()) {
            ctx.setFormUrlEncoded(true);
        }

        // Apply static @Headers values
        for (String[] kv : resolved.staticHeaders()) {
            ctx.addHeader(kv[0], kv[1]);
        }

        // Apply per-parameter handlers
        List<ParameterHandler> handlers = resolved.handlers();
        for (int i = 0; i < handlers.size(); i++) {
            Object arg = (args != null && i < args.length) ? args[i] : null;
            handlers.get(i).apply(ctx, arg);
        }

        HttpRequest request = ctx.buildRequest();

        // Run interceptor chain (interceptors may add headers, sign requests, etc.)
        try {
            request = new InterceptorChain(interceptors).proceed(request);
        } catch (IOException e) {
            throw new RestClientException("Interceptor chain failed: " + e.getMessage(), e);
        }

        log.debug("--> {} {}", request.method(), request.uri());
        log.debug("    Headers: {}", HeaderSanitizer.sanitize(request.headers()));

        return request;
    }

    // ── Synchronous execution ─────────────────────────────────────────────────

    private Object executeSync(HttpRequest request, ResolvedMethod resolved) {
        // FIX (P1): timer wraps the actual HTTP call, not the interceptor chain
        long start = System.currentTimeMillis();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RestClientException("HTTP call failed: " + e.getMessage(), e);
        }
        long elapsed = System.currentTimeMillis() - start;

        log.debug("<-- {} {} ({}ms)", response.statusCode(), request.uri(), elapsed);
        return handleResponse(response, resolved);
    }

    // ── Asynchronous execution ────────────────────────────────────────────────

    private CompletableFuture<?> executeAsync(HttpRequest request, ResolvedMethod resolved) {
        long start = System.currentTimeMillis();
        return httpClient
                .sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(response -> {
                    long elapsed = System.currentTimeMillis() - start;
                    log.debug("<-- {} {} async ({}ms)",
                            response.statusCode(), request.uri(), elapsed);
                    return handleResponse(response, resolved);
                });
    }

    // ── Response handling ─────────────────────────────────────────────────────

    private Object handleResponse(HttpResponse<InputStream> response,
                                  ResolvedMethod resolved) {
        int status = response.statusCode();

        if (status >= 400) {
            String body;
            try {
                body = new String(response.body().readAllBytes());
            } catch (IOException e) {
                body = "<unreadable>";
            }
            throw new ApiException(status, body);
        }

        // 204 No Content or void return
        if (status == 204 ||
                resolved.responseType().getRawClass() == Void.TYPE ||
                resolved.responseType().getRawClass() == Void.class) {
            return null;
        }

        // InputStream passthrough — caller reads directly
        if (resolved.responseType().getRawClass() == InputStream.class) {
            return response.body();
        }

        // Delegate to converter chain
        try (InputStream body = response.body()) {
            for (ResponseConverter converter : converters) {
                if (converter.canConvert(resolved.responseType())) {
                    return converter.convert(body, resolved.responseType());
                }
            }
            throw new RestClientException(
                    "No converter found for type: " + resolved.responseType());
        } catch (IOException e) {
            throw new RestClientException(
                    "Failed to deserialise response: " + e.getMessage(), e);
        }
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }
}
