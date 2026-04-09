package io.declarative.http.client;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.declarative.http.api.annotation.Body;
import io.declarative.http.api.annotation.DELETE;
import io.declarative.http.api.annotation.GET;
import io.declarative.http.api.annotation.Header;
import io.declarative.http.api.annotation.HeaderMap;
import io.declarative.http.api.annotation.PATCH;
import io.declarative.http.api.annotation.POST;
import io.declarative.http.api.annotation.PUT;
import io.declarative.http.api.annotation.Path;
import io.declarative.http.api.annotation.Query;
import io.declarative.http.api.annotation.QueryMap;
import io.declarative.http.error.RestClientException;
import io.declarative.http.handler.BodyHandler;
import io.declarative.http.handler.HeaderHandler;
import io.declarative.http.handler.HeaderMapHandler;
import io.declarative.http.handler.ParameterHandler;
import io.declarative.http.handler.PathHandler;
import io.declarative.http.handler.QueryHandler;
import io.declarative.http.handler.QueryMapHandler;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Parses and caches all annotation metadata for a single interface method.
 * Created ONCE when the client proxy is built; never re-parsed per call.
 */
public final class ResolvedMethod {

    private final String httpMethod;
    private final String pathTemplate;
    private final List<ParameterHandler> handlers;
    private final JavaType responseType;
    private final boolean isAsync; // true if return type is CompletableFuture

    private ResolvedMethod(String httpMethod, String pathTemplate,
                           List<ParameterHandler> handlers,
                           JavaType responseType, boolean isAsync) {
        this.httpMethod = httpMethod;
        this.pathTemplate = pathTemplate;
        this.handlers = List.copyOf(handlers);
        this.responseType = responseType;
        this.isAsync = isAsync;
    }

    /**
     * Factory method — call at proxy creation time, not per-invocation.
     */
    public static ResolvedMethod parse(Method method, ObjectMapper objectMapper) {
        // --- Resolve HTTP method and path ---
        String httpMethod;
        String path;

        if (method.isAnnotationPresent(GET.class)) {
            httpMethod = "GET";
            path = method.getAnnotation(GET.class).value();
        } else if (method.isAnnotationPresent(POST.class)) {
            httpMethod = "POST";
            path = method.getAnnotation(POST.class).value();
        } else if (method.isAnnotationPresent(PUT.class)) {
            httpMethod = "PUT";
            path = method.getAnnotation(PUT.class).value();
        } else if (method.isAnnotationPresent(PATCH.class)) {
            httpMethod = "PATCH";
            path = method.getAnnotation(PATCH.class).value();
        } else if (method.isAnnotationPresent(DELETE.class)) {
            httpMethod = "DELETE";
            path = method.getAnnotation(DELETE.class).value();
        } else {
            throw new RestClientException(
                    "Method '" + method.getName() +
                            "' has no HTTP method annotation (@GET, @POST, etc.)");
        }

        // --- Resolve parameter handlers ---
        Parameter[] params = method.getParameters();
        Annotation[][] paramAnnotations = method.getParameterAnnotations();
        List<ParameterHandler> handlers = new ArrayList<>(params.length);

        for (int i = 0; i < params.length; i++) {
            handlers.add(resolveHandler(params[i], paramAnnotations[i], method.getName()));
        }

        // --- Resolve return type ---
        Type returnType = method.getGenericReturnType();
        boolean isAsync = false;
        JavaType javaType;

        if (returnType instanceof ParameterizedType pt &&
                pt.getRawType() == CompletableFuture.class) {
            isAsync = true;
            javaType = objectMapper.constructType(pt.getActualTypeArguments()[0]);
        } else {
            javaType = objectMapper.constructType(returnType);
        }

        return new ResolvedMethod(httpMethod, path, handlers, javaType, isAsync);
    }

    private static ParameterHandler resolveHandler(
            Parameter param, Annotation[] annotations, String methodName) {

        for (Annotation ann : annotations) {
            if (ann instanceof Path p)       return new PathHandler(p.value(), p.encoded());
            if (ann instanceof Query q)      return new QueryHandler(q.value(), q.encoded());
            if (ann instanceof QueryMap qm)  return new QueryMapHandler(qm.encoded());
            if (ann instanceof Header h)     return new HeaderHandler(h.value());
            if (ann instanceof HeaderMap)    return new HeaderMapHandler();
            if (ann instanceof Body)         return new BodyHandler();
        }
        throw new RestClientException(
                "Parameter '" + param.getName() + "' in method '" + methodName +
                        "' has no recognized annotation. Use @Path, @Query, @QueryMap, " +
                        "@Header, @HeaderMap, or @Body.");
    }

    // --- Getters for InvocationDispatcher ---
    public String httpMethod()    { return httpMethod; }
    public String pathTemplate()  { return pathTemplate; }
    public List<ParameterHandler> handlers() { return handlers; }
    public JavaType responseType() { return responseType; }
    public boolean isAsync()      { return isAsync; }
}
