package io.declarative.http.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a service method body as {@code multipart/form-data}.
 *
 * <p>Each body contribution must be declared with {@link Part}. Multipart methods
 * cannot also use {@link Body}, {@link Field}, or {@link FormUrlEncoded}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Multipart {
}
