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
 * Internal engine that translates a resolved proxy method call into an HTTP exchange
 * and converts the response back into the declared Java return type.
 *
 * <p>{@code InvocationDispatcher} is the central execution hub of the library. It is
 * called by {@link NativeRestClient#create(Class)} for every intercepted method
 * invocation and orchestrates the following pipeline:
 *
 * <ol>
 *   <li><b>Request building</b> — a {@link RequestContext} accumulates all URI
 *       fragments, query parameters, headers, and body contributions from the
 *       registered {@link ParameterHandler} instances.</li>
 *   <li><b>Request interceptor chain</b> — the list of {@link ClientInterceptor}s
 *       can inspect or mutate the {@link HttpRequest} before it is sent
 *       (e.g. inject auth headers, add tracing IDs).</li>
 *   <li><b>HTTP exchange</b> — the final request is handed off to
 *       {@link HttpClient}, wrapped by the {@link HttpExchangeInterceptor} chain
 *       which can observe both the outbound request and inbound response
 *       (e.g. metrics, retry, response logging).</li>
 *   <li><b>Response handling</b> — the response body is converted via the
 *       {@link ResponseConverter} chain to the declared return type. Non-2xx
 *       responses throw {@link ApiException} unless the return type is
 *       {@link HttpResponseEnvelope}.</li>
 * </ol>
 *
 * <h2>Async vs Sync</h2>
 * <p>When the proxy method declares a {@link CompletableFuture} return type,
 * {@link HttpClient#sendAsync} is used and the future is returned immediately.
 * The {@link HttpExchangeInterceptor} chain is currently bypassed for async calls;
 * synchronous calls route through the full exchange-interceptor chain.
 *
 * <h2>Envelope Mode</h2>
 * <p>If the method's return type is {@code HttpResponseEnvelope<T>}, the dispatcher
 * wraps the response (status code, headers, and deserialised body) in an
 * {@link HttpResponseEnvelope} regardless of the HTTP status. This allows callers to
 * inspect 4xx/5xx responses without catching exceptions.
 *
 * <p>This class is package-private by design; it is not part of the public API.
 *
 * @see NativeRestClient
 * @see ResolvedMethod
 * @see RequestContext
 */
public final class InvocationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InvocationDispatcher.class);

    private final HttpClient httpClient;
    private final String baseUrl;
    private final ObjectMapper objectMapper;

    /** Immutable snapshot of request-only interceptors registered at build time. */
    private final List<ClientInterceptor> requestInterceptors;

    /** Ordered list of response converters; first match wins. */
    private final List<ResponseConverter> converters;

    /** Immutable snapshot of full-exchange interceptors registered at build time. */
    private final List<HttpExchangeInterceptor> exchangeInterceptors;

    /**
     * Constructs a dispatcher with the given infrastructure components.
     *
     * <p>All list arguments are defensively copied into immutable snapshots so that
     * post-construction mutation of the originals has no effect.
     *
     * @param httpClient          the JDK HTTP client used for sending requests
     * @param baseUrl             the root URL prefix, already stripped of trailing slash
     * @param objectMapper        Jackson mapper used for body serialisation/deserialisation
     * @param requestInterceptors ordered list of {@link ClientInterceptor}s applied before sending
     * @param converters          ordered list of {@link ResponseConverter}s for body deserialisation
     * @param exchangeInterceptors ordered list of {@link HttpExchangeInterceptor}s wrapping the exchange
     */
    public InvocationDispatcher(HttpClient httpClient,
                                String baseUrl,
                                ObjectMapper objectMapper,
                                List<ClientInterceptor> requestInterceptors,
                                List<ResponseConverter> converters,
                                List<HttpExchangeInterceptor> exchangeInterceptors) {
        this.httpClient           = httpClient;
        this.baseUrl              = baseUrl;
        this.objectMapper         = objectMapper;
        this.requestInterceptors  = List.copyOf(requestInterceptors);
        this.converters           = List.copyOf(converters);
        this.exchangeInterceptors = List.copyOf(exchangeInterceptors);
    }

    /**
     * Entry point called by the JDK dynamic proxy for every non-{@code Object} method.
     *
     * <p>Builds the HTTP request from {@code resolved} metadata and {@code args},
     * then delegates to either {@link #executeSync} or {@link #executeAsync} depending
     * on the method's declared return type.
     *
     * @param resolved pre-parsed metadata for the intercepted method (HTTP verb, path,
     *                 parameter handlers, response type, etc.)
     * @param args     runtime argument values passed to the proxy method; may be
     *                 {@code null} if the method has no parameters
     * @return the deserialised response body, a {@link CompletableFuture} for async
     *         methods, or {@code null} for {@code void} / HTTP 204 responses
     * @throws ApiException          on 4xx/5xx responses (non-envelope mode)
     * @throws RestClientException   on I/O failures, interceptor errors, or
     *                               deserialisation problems
     */
    public Object dispatch(ResolvedMethod resolved, Object[] args) {
        HttpRequest request = buildRequest(resolved, args);
        if (resolved.isAsync()) {
            return executeAsync(request, resolved);
        }
        return executeSync(request, resolved);
    }

    // ── Request building ──────────────────────────────────────────────────────

    /**
     * Constructs the outgoing {@link HttpRequest} from the resolved method descriptor
     * and runtime arguments.
     *
     * <p>Steps:
     * <ol>
     *   <li>Creates a {@link RequestContext} seeded with the HTTP method, base URL,
     *       and path template.</li>
     *   <li>Propagates the {@code @FormUrlEncoded} flag if present.</li>
     *   <li>Applies static {@code @Headers} key-value pairs.</li>
     *   <li>Iterates over all {@link ParameterHandler}s, pairing each with the
     *       corresponding runtime argument.</li>
     *   <li>Calls {@link RequestContext#buildRequest()} to produce the immutable
     *       {@link HttpRequest}.</li>
     *   <li>Runs the {@link ClientInterceptor} chain on the assembled request.</li>
     * </ol>
     *
     * @param resolved resolved method descriptor
     * @param args     runtime argument values
     * @return the final, interceptor-processed {@link HttpRequest}
     * @throws RestClientException if the interceptor chain throws {@link IOException}
     */
    private HttpRequest buildRequest(ResolvedMethod resolved, Object[] args) {
        RequestContext ctx = new RequestContext(
                resolved.httpMethod(), baseUrl, resolved.pathTemplate(), objectMapper);

        if (resolved.isFormUrlEncoded()) {
            ctx.setFormUrlEncoded(true);
        }

        for (String[] kv : resolved.staticHeaders()) {
            ctx.addHeader(kv[0], kv[1]);
        }

        List<ParameterHandler> handlers = resolved.handlers();
        for (int i = 0; i < handlers.size(); i++) {
            Object arg = (args != null && i < args.length) ? args[i] : null;
            handlers.get(i).apply(ctx, arg);
        }

        HttpRequest request = ctx.buildRequest();

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

    /**
     * Sends the request through the {@link HttpExchangeInterceptor} chain and returns
     * the raw {@link InputStream}-backed response.
     *
     * <p>The chain is assembled in reverse registration order so that the first
     * registered interceptor becomes the outermost wrapper. The terminal node
     * delegates to {@link HttpClient#send}.
     *
     * @param request the fully built HTTP request
     * @return the raw HTTP response with an {@link InputStream} body
     * @throws IOException          on network or I/O errors
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    private HttpResponse<InputStream> sendWithInterceptors(HttpRequest request)
            throws IOException, InterruptedException {

        HttpExchangeInterceptor.ExchangeChain<InputStream> terminal =
                req -> httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());

        HttpExchangeInterceptor.ExchangeChain<InputStream> chain = terminal;
        for (int i = exchangeInterceptors.size() - 1; i >= 0; i--) {
            HttpExchangeInterceptor interceptor = exchangeInterceptors.get(i);
            HttpExchangeInterceptor.ExchangeChain<InputStream> next = chain;
            chain = req -> interceptor.intercept(req, next);
        }

        return chain.proceed(request);
    }

    // ── Synchronous execution ─────────────────────────────────────────────────

    /**
     * Executes the HTTP call synchronously, blocking the calling thread until the
     * response is received.
     *
     * <p>Timing information is captured and logged at DEBUG level. On
     * {@link InterruptedException}, the thread's interrupt flag is restored before
     * re-throwing as a {@link RestClientException}.
     *
     * @param request  the assembled HTTP request
     * @param resolved resolved method descriptor used for response handling
     * @return the deserialised response body, or {@code null} for void/204
     * @throws RestClientException   on I/O failure or deserialisation error
     * @throws ApiException          on 4xx/5xx responses (non-envelope mode)
     */
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

    /**
     * Executes the HTTP call asynchronously using {@link HttpClient#sendAsync}.
     *
     * <p><b>Note:</b> the {@link HttpExchangeInterceptor} chain is currently <em>not</em>
     * applied in this path. A future enhancement may introduce an async-compatible
     * variant of {@link HttpExchangeInterceptor.ExchangeChain} for parity.
     *
     * @param request  the assembled HTTP request
     * @param resolved resolved method descriptor used for response handling
     * @return a {@link CompletableFuture} that completes with the deserialised response,
     *         or completes exceptionally on network error or non-2xx status
     */
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

    /**
     * Converts a raw HTTP response into the Java type declared by the proxy method.
     *
     * <p>The handling logic branches on two main factors:
     * <ul>
     *   <li><b>Envelope mode</b> ({@link ResolvedMethod#wrapInEnvelope()} is {@code true}):
     *       always wraps the result in {@link HttpResponseEnvelope}; never throws
     *       {@link ApiException} regardless of status code.</li>
     *   <li><b>Non-envelope mode</b>: throws {@link ApiException} for 4xx/5xx statuses.
     *       Returns {@code null} for 204 No Content or {@code void} return types.
     *       Passes through {@link InputStream} directly. Otherwise delegates to the
     *       converter chain.</li>
     * </ul>
     *
     * @param response the raw HTTP response with an {@link InputStream} body
     * @param resolved resolved method descriptor describing the expected return type
     * @return the deserialised value, an {@link HttpResponseEnvelope}, an
     *         {@link InputStream}, or {@code null}
     * @throws ApiException        if the status is &ge; 400 (non-envelope mode)
     * @throws RestClientException if the response body cannot be deserialised
     */
    private Object handleResponse(HttpResponse<InputStream> response,
                                  ResolvedMethod resolved) {
        int status = response.statusCode();

        if (resolved.wrapInEnvelope()) {
            if (status == 204 ||
                    resolved.responseType().getRawClass() == Void.TYPE ||
                    resolved.responseType().getRawClass() == Void.class) {
                return new HttpResponseEnvelope<>(status, response.headers(), null);
            }
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

        if (status >= 400) {
            String body;
            try {
                body = new String(response.body().readAllBytes());
            } catch (IOException e) {
                body = "<unreadable>";
            }
            throw new ApiException(status, body);
        }

        if (status == 204 ||
                resolved.responseType().getRawClass() == Void.TYPE ||
                resolved.responseType().getRawClass() == Void.class) {
            return null;
        }

        if (resolved.responseType().getRawClass() == InputStream.class) {
            return response.body();
        }

        try (InputStream body = response.body()) {
            return convertBody(body, resolved);
        } catch (IOException e) {
            throw new RestClientException(
                    "Failed to deserialise response: " + e.getMessage(), e);
        }
    }

    /**
     * Iterates the converter chain and delegates to the first converter that can
     * handle the declared response type.
     *
     * @param body     the raw response body {@link InputStream}; caller is responsible
     *                 for closing it after this call
     * @param resolved resolved method descriptor carrying the target {@link com.fasterxml.jackson.databind.JavaType}
     * @return the deserialised Java object
     * @throws IOException           if the converter encounters an I/O error
     * @throws RestClientException   if no converter supports the declared type
     */
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

    /**
     * Returns the shared {@link ObjectMapper} used by this dispatcher and by
     * {@link ResolvedMethod#parse} for generic type resolution.
     *
     * @return the Jackson object mapper; never {@code null}
     */
    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    /**
     * Returns the base URL this dispatcher prepends to every path template.
     *
     * @return the base URL string, without a trailing slash
     */
    public String baseUrl() {
        return baseUrl;
    }
}
