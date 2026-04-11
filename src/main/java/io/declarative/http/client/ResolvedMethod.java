package io.declarative.http.client;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.declarative.http.api.annotation.Body;
import io.declarative.http.api.annotation.DELETE;
import io.declarative.http.api.annotation.Field;
import io.declarative.http.api.annotation.FormUrlEncoded;
import io.declarative.http.api.annotation.GET;
import io.declarative.http.api.annotation.Header;
import io.declarative.http.api.annotation.HeaderMap;
import io.declarative.http.api.annotation.Headers;
import io.declarative.http.api.annotation.PATCH;
import io.declarative.http.api.annotation.POST;
import io.declarative.http.api.annotation.PUT;
import io.declarative.http.api.annotation.Path;
import io.declarative.http.api.annotation.Query;
import io.declarative.http.api.annotation.QueryMap;
import io.declarative.http.api.annotation.Url;
import io.declarative.http.error.RestClientException;
import io.declarative.http.handler.BodyHandler;
import io.declarative.http.handler.FieldHandler;
import io.declarative.http.handler.HeaderHandler;
import io.declarative.http.handler.HeaderMapHandler;
import io.declarative.http.handler.ParameterHandler;
import io.declarative.http.handler.PathHandler;
import io.declarative.http.handler.QueryHandler;
import io.declarative.http.handler.QueryMapHandler;
import io.declarative.http.handler.UrlHandler;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Immutable resolved metadata for a single proxy method.
 */
public final class ResolvedMethod {

    private final String httpMethod;
    private final String pathTemplate;
    private final List<ParameterHandler> handlers;
    private final JavaType responseType;
    private final boolean isAsync;
    private final List<String[]> staticHeaders;   // from @Headers
    private final boolean formUrlEncoded;          // from @FormUrlEncoded
    private final boolean wrapInEnvelope;

    private ResolvedMethod(String httpMethod, String pathTemplate, List<ParameterHandler> handlers,
                           JavaType responseType, boolean isAsync, List<String[]> staticHeaders,
                           boolean formUrlEncoded, boolean wrapInEnvelope) {
        this.httpMethod = httpMethod;
        this.pathTemplate = pathTemplate;
        this.handlers = Collections.unmodifiableList(handlers);
        this.responseType = responseType;
        this.isAsync = isAsync;
        this.staticHeaders = Collections.unmodifiableList(staticHeaders);
        this.formUrlEncoded = formUrlEncoded;
        this.wrapInEnvelope = wrapInEnvelope;
    }


    public String httpMethod() {
        return httpMethod;
    }

    public String pathTemplate() {
        return pathTemplate;
    }

    public List<ParameterHandler> handlers() {
        return handlers;
    }

    public JavaType responseType() {
        return responseType;
    }

    public boolean isAsync() {
        return isAsync;
    }

    public List<String[]> staticHeaders() {
        return staticHeaders;
    }

    public boolean isFormUrlEncoded() {
        return formUrlEncoded;
    }

    public boolean wrapInEnvelope() {
        return wrapInEnvelope;
    }


    public static ResolvedMethod parse(Method method, ObjectMapper objectMapper) {
        // HTTP verb
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

        // @FormUrlEncoded — method-level
        boolean isForm = method.isAnnotationPresent(FormUrlEncoded.class);

        // @Headers — static key: value pairs
        List<String[]> staticHdrs = new ArrayList<>();
        if (method.isAnnotationPresent(Headers.class)) {
            for (String header : method.getAnnotation(Headers.class).value()) {
                int colon = header.indexOf(':');
                if (colon < 1) {
                    throw new RestClientException("@Headers value must be 'Name: Value', got: '" + header + "'");
                }
                staticHdrs.add(new String[]{header.substring(0, colon).trim(), header.substring(colon + 1).trim()});
            }
        }

        // Parameter handlers
        Annotation[][] paramAnnotations = method.getParameterAnnotations();
        List<ParameterHandler> handlers = new ArrayList<>();
        for (int i = 0; i < paramAnnotations.length; i++) {
            handlers.add(resolveHandler(method, i, paramAnnotations[i], isForm));
        }

        // Return type
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
            JavaType inner = declaredType.containedTypeOrUnknown(0);
            responseType = inner;
        } else {
            responseType = declaredType;
        }

        return new ResolvedMethod(httpVerb, path, handlers, responseType, async, staticHdrs, isForm, wrap);
    }

    private static ParameterHandler resolveHandler(Method method, int index, Annotation[] annotations, boolean isForm) {
        for (Annotation ann : annotations) {
            if (ann instanceof Path p) return new PathHandler(p.value(), p.encoded());
            if (ann instanceof Query q) return new QueryHandler(q.value(), q.encoded());
            if (ann instanceof QueryMap qm) return new QueryMapHandler(qm.encoded());
            if (ann instanceof Header h) return new HeaderHandler(h.value());
            if (ann instanceof HeaderMap) return new HeaderMapHandler();
            if (ann instanceof Body) return new BodyHandler();
            if (ann instanceof Url) return new UrlHandler();
            if (ann instanceof Field f) return new FieldHandler(f.value(), f.encoded());
        }
        throw new RestClientException("Parameter " + index + " of '" + method.getName()
                + "' has no recognised annotation. Supported: "
                + "@Path, @Query, @QueryMap, @Header, @HeaderMap, @Body, @Url, @Field");
    }

    public static void validateInterface(Class<?> service) {
        if (!service.isInterface()) {
            throw new RestClientException(service.getName() + " is not an interface");
        }
        if (service.getDeclaredMethods().length == 0) {
            throw new RestClientException(service.getName() + " declares no methods — nothing to proxy");
        }
    }
}
