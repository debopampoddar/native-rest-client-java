package io.declarative.http.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.declarative.http.api.converters.ResponseConverter;
import io.declarative.http.api.interceptors.ClientInterceptor;
import io.declarative.http.api.interceptors.HttpExchangeInterceptor;
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
 * Responsibilities:
 * <ul>
 *   <li>Apply parameter handlers and build the base HttpRequest</li>
 *   <li>Run request-only ClientInterceptor chain</li>
 *   <li>Run HttpExchangeInterceptor chain around HttpClient.send(..)</li>
 *   <li>Convert the response body via ResponseConverter chain</li>
 *   <li>Throw ApiException on non-2xx (unless using HttpResponseEnvelope<T>)</li>
 * </ul>
 */
public final class InvocationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InvocationDispatcher.class);

    private final HttpClient httpClient;
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final List<ClientInterceptor> requestInterceptors;
    private final List<ResponseConverter> converters;
    private final List<HttpExchangeInterceptor> exchangeInterceptors;

    public InvocationDispatcher(HttpClient httpClient,
                                String baseUrl,
                                ObjectMapper objectMapper,
                                List<ClientInterceptor> requestInterceptors,
                                List<ResponseConverter> converters,
                                List<HttpExchangeInterceptor> exchangeInterceptors) {
        this.httpClient          = httpClient;
        this.baseUrl             = baseUrl;
        this.objectMapper        = objectMapper;
        this.requestInterceptors = List.copyOf(requestInterceptors);
        this.converters          = List.copyOf(converters);
        this.exchangeInterceptors = List.copyOf(exchangeInterceptors);
    }

    /**
     * Entry point used by the dynamic proxy.
     */
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

        // Run request-only interceptor chain (auth, custom headers, etc.)
        try {
            request = new InterceptorChain(requestInterceptors).proceed(request);
        } catch (IOException e) {
            throw new RestClientException("Interceptor chain failed: " + e.getMessage(), e);
        }

        log.debug("--> {} {}", request.method(), request.uri());
        log.debug("    Headers: {}", HeaderSanitizer.sanitize(request.headers()));

        return request;
    }

    // ── Exchange interceptor chain ────────────────────────────────────────────

    private HttpResponse<InputStream> sendWithInterceptors(HttpRequest request)
            throws IOException, InterruptedException {

        // Terminal node: actual HttpClient call
        HttpExchangeInterceptor.ExchangeChain<InputStream> terminal =
                req -> httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());

        HttpExchangeInterceptor.ExchangeChain<InputStream> chain = terminal;

        // Wrap in reverse registration order
        for (int i = exchangeInterceptors.size() - 1; i >= 0; i--) {
            HttpExchangeInterceptor interceptor = exchangeInterceptors.get(i);
            HttpExchangeInterceptor.ExchangeChain<InputStream> next = chain;
            chain = req -> interceptor.intercept(req, next);
        }

        return chain.proceed(request);
    }

    // ── Synchronous execution ─────────────────────────────────────────────────

    private Object executeSync(HttpRequest request, ResolvedMethod resolved) {
        long start = System.currentTimeMillis();
        HttpResponse<InputStream> response;
        try {
            response = sendWithInterceptors(request);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RestClientException("HTTP call failed: " + e.getMessage(), e);
        }
        long elapsed = System.currentTimeMillis() - start;

        log.debug("<-- {} {} ({}ms)", response.statusCode(), request.uri(), elapsed);
        return handleResponse(response, resolved);
    }

    // ── Asynchronous execution ────────────────────────────────────────────────
    //
    // Note: for simplicity this path currently bypasses HttpExchangeInterceptor
    // and uses HttpClient.sendAsync directly. You can later introduce an async
    // variant of ExchangeChain if you want identical behavior for async calls.
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

        // Envelope mode: always return HttpResponseEnvelope<T> (no ApiException)
        if (resolved.wrapInEnvelope()) {
            // 204 No Content or void return
            if (status == 204 ||
                    resolved.responseType().getRawClass() == Void.TYPE ||
                    resolved.responseType().getRawClass() == Void.class) {
                return new HttpResponseEnvelope<>(status, response.headers(), null);
            }

            // InputStream passthrough
            if (resolved.responseType().getRawClass() == InputStream.class) {
                return new HttpResponseEnvelope<>(status, response.headers(), response.body());
            }

            try (InputStream body = response.body()) {
                Object deserialised = convertBody(body, resolved);
                return new HttpResponseEnvelope<>(status, response.headers(), deserialised);
            } catch (IOException e) {
                throw new RestClientException(
                        "Failed to deserialise response: " + e.getMessage(), e);
            }
        }

        // Non-envelope mode: throw on 4xx/5xx
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
            return convertBody(body, resolved);
        } catch (IOException e) {
            throw new RestClientException(
                    "Failed to deserialise response: " + e.getMessage(), e);
        }
    }

    private Object convertBody(InputStream body, ResolvedMethod resolved) throws IOException {
        for (ResponseConverter converter : converters) {
            if (converter.canConvert(resolved.responseType())) {
                return converter.convert(body, resolved.responseType());
            }
        }
        throw new RestClientException(
                "No converter found for type: " + resolved.responseType());
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    public String baseUrl() {
        return baseUrl;
    }
}
