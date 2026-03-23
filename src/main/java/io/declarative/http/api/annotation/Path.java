package io.declarative.http.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that the annotated parameter should be used to substitute a path segment in the URL.
 *
 * @author Debopam
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Path {
    /**
     * The name of the path segment in the URL template to be substituted by the parameter value.
     *
     * @return the path segment name
     */
    String value();
}
