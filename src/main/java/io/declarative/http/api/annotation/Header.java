package io.declarative.http.api.annotation;
import java.lang.annotation.*;

/**
 * Adds a single HTTP request header whose value is supplied at call time.
 *
 * <p>If the value is {@code null}, the header is silently omitted. Use
 * {@link Headers @Headers} for static, compile-time headers.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @GET("/data")
 * Data getData(@Header("X-Correlation-Id") String correlationId);
 * }</pre>
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Header {
    /** The HTTP header name, e.g. {@code "X-Correlation-Id"}. */
    String value();
}
