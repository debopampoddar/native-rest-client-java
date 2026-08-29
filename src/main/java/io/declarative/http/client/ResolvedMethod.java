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
 *   <li><b>Multipart flag</b> — set when {@link Multipart} is present on the method.</li>
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

    /** {@code true} when the method is annotated with {@link Multipart}. */
    private final boolean multipart;

    /**
     * {@code true} when the return type (after unwrapping async) is
     * {@link HttpResponseEnvelope}, meaning the full response is surfaced to the caller.
     */
    private final boolean wrapInEnvelope;

    /** Private constructor — instances are created exclusively through {@link #parse}. */
    private ResolvedMethod(String httpMethod, String pathTemplate,
                           List<ParameterHandler> handlers, JavaType responseType,
                           boolean isAsync, List<String[]> staticHeaders,
                           boolean formUrlEncoded, boolean multipart,
                           boolean wrapInEnvelope) {
        this.httpMethod     = httpMethod;
        this.pathTemplate   = pathTemplate;
        this.handlers       = Collections.unmodifiableList(handlers);
        this.responseType   = responseType;
        this.isAsync        = isAsync;
        this.staticHeaders  = Collections.unmodifiableList(staticHeaders);
        this.formUrlEncoded = formUrlEncoded;
        this.multipart      = multipart;
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
     * Returns {@code true} if the method carries {@link Multipart}, indicating
     * that {@code @Part} arguments are serialised as {@code multipart/form-data}.
     *
     * @return {@code true} for multipart requests
     */
    public boolean isMultipart() { return multipart; }

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
     * <p>This method is called once per method while
     * {@link NativeRestClient#create(Class)} validates a service proxy. The result
     * is cached in {@link NativeRestClient}'s method cache.
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
        int verbCount = countHttpVerbs(method);
        if (verbCount != 1) {
            throw new RestClientException("Method '" + method.getName()
                    + "' must declare exactly one HTTP verb annotation");
        }

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
            throw new AssertionError("Unreachable HTTP verb state");
        }

        boolean isForm = method.isAnnotationPresent(FormUrlEncoded.class);
        boolean isMultipart = method.isAnnotationPresent(Multipart.class);

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
        Class<?>[] parameterTypes = method.getParameterTypes();
        validateParameters(method, paramAnnotations, parameterTypes, isForm, isMultipart);
        List<ParameterHandler> handlers = new ArrayList<>();
        for (int i = 0; i < paramAnnotations.length; i++) {
            handlers.add(resolveHandler(method, i, paramAnnotations[i], parameterTypes[i]));
        }

        boolean wrap = false;
        TypeFactory tf = objectMapper.getTypeFactory();
        boolean async = method.getReturnType() == CompletableFuture.class;
        JavaType responseType;

        Type declared;
        if (async) {
            if (!(method.getGenericReturnType() instanceof ParameterizedType futureType)) {
                throw new RestClientException("Method '" + method.getName()
                        + "' must return CompletableFuture<T>");
            }
            declared = futureType.getActualTypeArguments()[0];
        } else {
            declared = method.getGenericReturnType();
        }

        JavaType declaredType = tf.constructType(declared);
        if (declaredType.getRawClass() == HttpResponseEnvelope.class) {
            if (!(declared instanceof ParameterizedType)) {
                throw new RestClientException("Method '" + method.getName()
                        + "' must return HttpResponseEnvelope<T>");
            }
            wrap = true;
            responseType = declaredType.containedTypeOrUnknown(0);
        } else {
            responseType = declaredType;
        }

        return new ResolvedMethod(httpVerb, path, handlers, responseType,
                async, staticHdrs, isForm, isMultipart, wrap);
    }

    /**
     * Counts HTTP verb annotations so a service method cannot ambiguously declare
     * more than one transport operation.
     *
     * @param method service method being parsed
     * @return number of recognised HTTP verb annotations
     */
    private static int countHttpVerbs(Method method) {
        int count = 0;
        if (method.isAnnotationPresent(GET.class)) count++;
        if (method.isAnnotationPresent(POST.class)) count++;
        if (method.isAnnotationPresent(PUT.class)) count++;
        if (method.isAnnotationPresent(DELETE.class)) count++;
        if (method.isAnnotationPresent(PATCH.class)) count++;
        return count;
    }

    /**
     * Validates cross-parameter rules before creating reusable handler objects.
     *
     * <p>The checks make declaration failures deterministic at proxy creation:
     * exactly one binding per ordinary parameter, at most one body or URL,
     * mutually exclusive form/multipart modes, and a final unannotated
     * {@link RequestOptions} parameter when present.
     *
     * @param method service method being parsed
     * @param parameterAnnotations annotations for each declared parameter
     * @param parameterTypes declared Java parameter types
     * @param formEncoded whether the method carries {@link FormUrlEncoded}
     * @param multipart whether the method carries {@link Multipart}
     * @throws RestClientException if the declaration is ambiguous or incompatible
     */
    private static void validateParameters(Method method,
                                           Annotation[][] parameterAnnotations,
                                           Class<?>[] parameterTypes,
                                           boolean formEncoded,
                                           boolean multipart) {
        int bodyCount = 0;
        int fieldCount = 0;
        int partCount = 0;
        int urlCount = 0;
        for (int i = 0; i < parameterAnnotations.length; i++) {
            if (parameterTypes[i] == RequestOptions.class) {
                if (parameterAnnotations[i].length != 0) {
                    throw new RestClientException("RequestOptions parameter " + i + " of '"
                            + method.getName() + "' must not declare a binding annotation");
                }
                if (i != parameterAnnotations.length - 1) {
                    throw new RestClientException("RequestOptions parameter of '" + method.getName()
                            + "' must be the final parameter");
                }
                continue;
            }
            int supportedCount = 0;
            for (Annotation annotation : parameterAnnotations[i]) {
                if (annotation instanceof Path || annotation instanceof Query
                        || annotation instanceof QueryMap || annotation instanceof Header
                        || annotation instanceof HeaderMap || annotation instanceof Body
                        || annotation instanceof Url || annotation instanceof Field
                        || annotation instanceof Part) {
                    supportedCount++;
                }
                if (annotation instanceof Body) bodyCount++;
                if (annotation instanceof Field) fieldCount++;
                if (annotation instanceof Part part) {
                    if (part.value().isBlank()) {
                        throw new RestClientException("@Part value on parameter " + i + " of '"
                                + method.getName() + "' must not be blank");
                    }
                    partCount++;
                }
                if (annotation instanceof Url) urlCount++;
            }
            if (supportedCount == 0) {
                throw new RestClientException("Parameter " + i + " of '"
                        + method.getName() + "' has no recognised annotation");
            }
            if (supportedCount > 1) {
                throw new RestClientException("Parameter " + i + " of '"
                        + method.getName() + "' must declare exactly one binding annotation");
            }
        }
        if (bodyCount > 1) {
            throw new RestClientException("Method '" + method.getName()
                    + "' must not declare multiple @Body parameters");
        }
        if (urlCount > 1) {
            throw new RestClientException("Method '" + method.getName()
                    + "' must not declare multiple @Url parameters");
        }
        if (formEncoded && bodyCount > 0) {
            throw new RestClientException("Method '" + method.getName()
                    + "' must not combine @FormUrlEncoded with @Body");
        }
        if (formEncoded && fieldCount == 0) {
            throw new RestClientException("Method '" + method.getName()
                    + "' uses @FormUrlEncoded but declares no @Field parameters");
        }
        if (!formEncoded && fieldCount > 0) {
            throw new RestClientException("Method '" + method.getName()
                    + "' declares @Field without @FormUrlEncoded");
        }
        if (formEncoded && multipart) {
            throw new RestClientException("Method '" + method.getName()
                    + "' must not combine @FormUrlEncoded with @Multipart");
        }
        if (multipart && (bodyCount > 0 || fieldCount > 0)) {
            throw new RestClientException("Method '" + method.getName()
                    + "' must not combine @Multipart with @Body or @Field");
        }
        if (multipart && partCount == 0) {
            throw new RestClientException("Method '" + method.getName()
                    + "' uses @Multipart but declares no @Part parameters");
        }
        if (!multipart && partCount > 0) {
            throw new RestClientException("Method '" + method.getName()
                    + "' declares @Part without @Multipart");
        }
    }

    /**
     * Maps a single method parameter's annotations to the appropriate
     * {@link ParameterHandler} implementation.
     *
     * <p>Supported annotations (in evaluation order):
     * {@link Path}, {@link Query}, {@link QueryMap}, {@link Header},
     * {@link HeaderMap}, {@link Body}, {@link Url}, {@link Field}, {@link Part},
     * or an unannotated final {@link RequestOptions} parameter.
     *
     * @param method      the declaring method (used only for error messages)
     * @param index       zero-based parameter index (used in error messages)
     * @param annotations all annotations present on the parameter
     * @param parameterType declared Java type of the parameter
     * @return the matching {@link ParameterHandler}
     * @throws RestClientException if no supported annotation is found on the parameter
     */
    private static ParameterHandler resolveHandler(Method method, int index,
                                                   Annotation[] annotations,
                                                   Class<?> parameterType) {
        if (parameterType == RequestOptions.class) {
            return new RequestOptionsHandler();
        }
        for (Annotation ann : annotations) {
            if (ann instanceof Path p)      return new PathHandler(p.value(), p.encoded());
            if (ann instanceof Query q)     return new QueryHandler(q.value(), q.encoded());
            if (ann instanceof QueryMap qm) return new QueryMapHandler(qm.encoded());
            if (ann instanceof Header h)    return new HeaderHandler(h.value());
            if (ann instanceof HeaderMap)   return new HeaderMapHandler();
            if (ann instanceof Body)        return new BodyHandler();
            if (ann instanceof Url)         return new UrlHandler();
            if (ann instanceof Field f)     return new FieldHandler(f.value(), f.encoded());
            if (ann instanceof Part p)      return new PartHandler(p.value());
        }
        throw new RestClientException("Parameter " + index + " of '" + method.getName()
                + "' has no recognised annotation. Supported: "
                + "@Path, @Query, @QueryMap, @Header, @HeaderMap, @Body, @Url, @Field, @Part");
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
        boolean hasServiceMethod = Arrays.stream(service.getMethods())
                .anyMatch(method -> method.getDeclaringClass() != Object.class
                        && !method.isDefault()
                        && !java.lang.reflect.Modifier.isStatic(method.getModifiers()));
        if (!hasServiceMethod) {
            throw new RestClientException(
                    service.getName() + " declares no methods — nothing to proxy");
        }
    }
}
