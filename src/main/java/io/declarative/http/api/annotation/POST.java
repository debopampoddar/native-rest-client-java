package io.declarative.http.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that the annotated method represents an HTTP POST request.
 *
 * @author Debopam
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface POST {
    /**
     * The relative URL path for the POST request.
     *
     * @return the endpoint path
     */
    String value() default "";
}
