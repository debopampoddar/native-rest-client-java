package io.declarative.http.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Adds static headers to an API method.
 * <p>
 * Each header is a string in the format "Name: Value".
 *
 * <pre><code>
 * {@literal @}Headers({
 *     "Accept: application/json",
 *     "User-Agent: My-App/1.0"
 * })
 * {@literal @}GET("/api/data")
 * void getData();
 * </code></pre>
 *
 * @author Debopam
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Headers {
    /**
     * An array of header strings.
     *
     * @return the headers
     */
    String[] value();
}
