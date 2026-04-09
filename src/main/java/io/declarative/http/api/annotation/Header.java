package io.declarative.http.api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Adds a dynamic request header to an API method.
 * <p>
 * The value of the annotated parameter will be used as the header's value.
 *
 * <pre><code>
 * {@literal @}GET("/api/data")
 * void getData(@Header("Authorization") String token);
 * </code></pre>
 *
 * @author Debopam
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@Documented
public @interface Header {
    /**
     * The name of the HTTP header.
     *
     * @return the header name
     */
    String value();
}
