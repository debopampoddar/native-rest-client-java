package io.declarative.http.client;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.declarative.http.api.annotation.*;
import io.declarative.http.error.RestClientException;
import io.declarative.http.handler.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Immutable, pre-parsed descriptor for a single annotated service interface method.
 *
 * <p>Parsing happens exactly once per method via {@link #parse(Method, ObjectMapper)}
 * and the result is cached in {@link NativeRestClient}'s method cache. All subsequent
 * invocations of that proxy method use the cached descriptor, eliminating repeated
 * reflection overhead at call time.
 *
 * <h2>What is resolved</h2>
 * <ul>
 *   <li><b>HTTP verb and path template</b> — extracted from {@link GET}, {@link POST},
 *       {@link PUT}, {@link DELETE}, or {@link PATCH} annotations.</li>
 *   <li><b>Static headers</b> — the {@code "Name: Value"} strings declared on
 *       {@link Headers} are parsed and stored as two-element {@code String[]} arrays.</li>
 *   <li><b>Parameter handlers</b> — one {@link ParameterHandler} is created per method
 *       parameter by inspecting its annotation ({@link Path}, {@link Query}, {@link Body},
 *       etc.). The handlers are applied at call time in index order.</li>
 *   <li><b>Response type</b> — the Jackson {@link JavaType} for the declared return type,
 *       unwrapped from {@link CompletableFuture} and/or {@link HttpResponseEnvelope} if
 *       present.</li>
 *   <li><b>Async flag</b> — set when the return type is {@link CompletableFuture}.</li>
 *   <li><b>Envelope flag</b> — set when the (possibly unwrapped) return type is
 *       {@link HttpResponseEnvelope}, indicating that the caller wants raw status/header
 *       access rather than deserialized-body-only delivery.</li>
 *   <li><b>Form URL encoded flag</b> — set when {@link FormUrlEncoded} is present on
 *       the method.</li>
 * </ul>
 *
 * @see NativeRestClient#create(Class)
 * @see InvocationDispatcher#dispatch(ResolvedMethod, Object[])
 */
public final class ResolvedMethod {

    /** The HTTP verb string, e.g. {@code "GET"}, {@code "POST"}. */
    private final String httpMethod;

    /** Path segment from the HTTP verb annotation, e.g. {@code "/users/{id}"}. */
    private final String pathTemplate;

    /**
     * Ordered list of parameter handlers. The handler at index {@code i} processes
     * the runtime argument at the same index.
     */
    private final List<ParameterHandler> handlers;

    /** The Jackson target type for response body deserialisation. */
    private final JavaType responseType;

    /** {@code true} when the method return type is {@link CompletableFuture}. */
    private final boolean isAsync;

    /**
     * Static headers parsed from {@link Headers}; each element is a two-element
     * {@code String[]} of {@code {name, value}}.
     */
    private final List<String[]> staticHeaders;

    /** {@code true} when the method is annotated with {@link FormUrlEncoded}. */
    private final boolean formUrlEncoded;

    /**
     * {@code true} when the return type (after unwrapping async) is
     * {@link HttpResponseEnvelope}, meaning the full response is surfaced to the caller.
     */
    private final boolean wrapInEnvelope;

    /** Private constructor — instances are created exclusively through {@link #parse}. */
    private ResolvedMethod(String httpMethod, String pathTemplate,
                           List<ParameterHandler> handlers, JavaType responseType,
                           boolean isAsync, List<String[]> staticHeaders,
                           boolean formUrlEncoded, boolean wrapInEnvelope) {
        this.httpMethod     = httpMethod;
        this.pathTemplate   = pathTemplate;
        this.handlers       = Collections.unmodifiableList(handlers);
        this.responseType   = responseType;
        this.isAsync        = isAsync;
        this.staticHeaders  = Collections.unmodifiableList(staticHeaders);
        this.formUrlEncoded = formUrlEncoded;
        this.wrapInEnvelope = wrapInEnvelope;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /**
     * Returns the HTTP verb for this method, e.g. {@code "GET"} or {@code "POST"}.
     *
     * @return the uppercase HTTP method string; never {@code null}
     */
    public String httpMethod() { return httpMethod; }

    /**
     * Returns the path template from the HTTP verb annotation, e.g. {@code "/users/{id}"}.
     *
     * <p>Path variables enclosed in {@code {}} are substituted at call time by
     * {@link io.declarative.http.handler.PathHandler}.
     *
     * @return the raw path template string; never {@code null}
     */
    public String pathTemplate() { return pathTemplate; }

    /**
     * Returns the ordered, immutable list of {@link ParameterHandler}s for this method.
     *
     * <p>The handler at position {@code i} processes the runtime argument at index {@code i}.
     *
     * @return an unmodifiable list of parameter handlers; never {@code null}
     */
    public List<ParameterHandler> handlers() { return handlers; }

    /**
     * Returns the Jackson {@link JavaType} representing the response body target type.
     *
     * <p>This is the type that the converter chain will deserialise into. For envelope
     * mode ({@link #wrapInEnvelope()}), this is the inner type {@code T} of
     * {@code HttpResponseEnvelope<T>}.
     *
     * @return the resolved Jackson type; never {@code null}
     */
    public JavaType responseType() { return responseType; }

    /**
     * Returns {@code true} if the method declared a {@link CompletableFuture} return type,
     * indicating that the HTTP call should be dispatched asynchronously.
     *
     * @return {@code true} for async methods
     */
    public boolean isAsync() { return isAsync; }

    /**
     * Returns the static headers parsed from the method's {@link Headers} annotation.
     *
     * <p>Each element is a two-element {@code String[]} where index {@code 0} is the
     * header name and index {@code 1} is the header value.
     *
     * @return an unmodifiable list of name-value pairs; empty if no {@code @Headers} present
     */
    public List<String[]> staticHeaders() { return staticHeaders; }

    /**
     * Returns {@code true} if the method carries {@link FormUrlEncoded}, indicating
     * that the request body should be serialised as {@code application/x-www-form-urlencoded}.
     *
     * @return {@code true} for form-encoded requests
     */
    public boolean isFormUrlEncoded() { return formUrlEncoded; }

    /**
     * Returns {@code true} if the method's return type (after async unwrapping) is
     * {@link HttpResponseEnvelope}, meaning the raw HTTP status and headers are exposed
     * to the caller without throwing {@link io.declarative.http.error.ApiException}.
     *
     * @return {@code true} for envelope-mode methods
     */
    public boolean wrapInEnvelope() { return wrapInEnvelope; }

    // ── Parsing ───────────────────────────────────────────────────────────────

    /**
     * Parses the given {@link Method} and produces an immutable {@link ResolvedMethod}
     * descriptor ready for repeated use at call time.
     *
     * <p>This method is called once per method, on the first invocation of the proxy.
     * The result is cached in {@link NativeRestClient#methodCache}.
     *
     * @param method       the reflected service interface method to parse
     * @param objectMapper Jackson mapper whose {@link TypeFactory} is used to construct
     *                     the response {@link JavaType}
     * @return the fully resolved, immutable descriptor
     * @throws RestClientException if the method lacks an HTTP verb annotation, if a
     *         {@code @Headers} value is malformed, or if a parameter has no recognised
     *         binding annotation
     */
    public static ResolvedMethod parse(Method method, ObjectMapper objectMapper) {
        String httpVerb;
        String path;

        if (method.isAnnotationPresent(GET.class)) {
            httpVerb = "GET";
            path = method.getAnnotation(GET.class).value();
        } else if (method.isAnnotationPresent(POST.class)) {
            httpVerb = "POST";
            path = method.getAnnotation(POST.class).value();
        } else if (method.isAnnotationPresent(PUT.class)) {
            httpVerb = "PUT";
            path = method.getAnnotation(PUT.class).value();
        } else if (method.isAnnotationPresent(DELETE.class)) {
            httpVerb = "DELETE";
            path = method.getAnnotation(DELETE.class).value();
        } else if (method.isAnnotationPresent(PATCH.class)) {
            httpVerb = "PATCH";
            path = method.getAnnotation(PATCH.class).value();
        } else {
            throw new RestClientException("Method '" + method.getName()
                    + "' has no HTTP verb annotation "
                    + "(expected @GET, @POST, @PUT, @DELETE, or @PATCH)");
        }

        boolean isForm = method.isAnnotationPresent(FormUrlEncoded.class);

        List<String[]> staticHdrs = new ArrayList<>();
        if (method.isAnnotationPresent(Headers.class)) {
            for (String header : method.getAnnotation(Headers.class).value()) {
                int colon = header.indexOf(':');
                if (colon < 1) {
                    throw new RestClientException(
                            "@Headers value must be 'Name: Value', got: '" + header + "'");
                }
                staticHdrs.add(new String[]{
                        header.substring(0, colon).trim(),
                        header.substring(colon + 1).trim()
                });
            }
        }

        Annotation[][] paramAnnotations = method.getParameterAnnotations();
        List<ParameterHandler> handlers = new ArrayList<>();
        for (int i = 0; i < paramAnnotations.length; i++) {
            handlers.add(resolveHandler(method, i, paramAnnotations[i], isForm));
        }

        boolean wrap = false;
        TypeFactory tf = objectMapper.getTypeFactory();
        boolean async = method.getReturnType() == CompletableFuture.class;
        JavaType responseType;

        Type declared = async
                ? ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments()[0]
                : method.getGenericReturnType();

        JavaType declaredType = tf.constructType(declared);
        if (declaredType.getRawClass() == HttpResponseEnvelope.class) {
            wrap = true;
            responseType = declaredType.containedTypeOrUnknown(0);
        } else {
            responseType = declaredType;
        }

        return new ResolvedMethod(httpVerb, path, handlers, responseType,
                async, staticHdrs, isForm, wrap);
    }

    /**
     * Maps a single method parameter's annotations to the appropriate
     * {@link ParameterHandler} implementation.
     *
     * <p>Supported annotations (in evaluation order):
     * {@link Path}, {@link Query}, {@link QueryMap}, {@link Header},
     * {@link HeaderMap}, {@link Body}, {@link Url}, {@link Field}.
     *
     * @param method      the declaring method (used only for error messages)
     * @param index       zero-based parameter index (used in error messages)
     * @param annotations all annotations present on the parameter
     * @param isForm      {@code true} if the method carries {@link FormUrlEncoded}
     * @return the matching {@link ParameterHandler}
     * @throws RestClientException if no supported annotation is found on the parameter
     */
    private static ParameterHandler resolveHandler(Method method, int index,
                                                   Annotation[] annotations,
                                                   boolean isForm) {
        for (Annotation ann : annotations) {
            if (ann instanceof Path p)      return new PathHandler(p.value(), p.encoded());
            if (ann instanceof Query q)     return new QueryHandler(q.value(), q.encoded());
            if (ann instanceof QueryMap qm) return new QueryMapHandler(qm.encoded());
            if (ann instanceof Header h)    return new HeaderHandler(h.value());
            if (ann instanceof HeaderMap)   return new HeaderMapHandler();
            if (ann instanceof Body)        return new BodyHandler();
            if (ann instanceof Url)         return new UrlHandler();
            if (ann instanceof Field f)     return new FieldHandler(f.value(), f.encoded());
        }
        throw new RestClientException("Parameter " + index + " of '" + method.getName()
                + "' has no recognised annotation. Supported: "
                + "@Path, @Query, @QueryMap, @Header, @HeaderMap, @Body, @Url, @Field");
    }

    /**
     * Validates that the supplied class is a non-empty interface suitable for proxying.
     *
     * @param service the class to validate
     * @throws RestClientException if {@code service} is not an interface or declares
     *                             no methods
     */
    public static void validateInterface(Class<?> service) {
        if (!service.isInterface()) {
            throw new RestClientException(service.getName() + " is not an interface");
        }
        if (service.getDeclaredMethods().length == 0) {
            throw new RestClientException(
                    service.getName() + " declares no methods — nothing to proxy");
        }
    }
}
