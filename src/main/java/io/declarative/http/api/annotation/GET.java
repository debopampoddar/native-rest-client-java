package io.declarative.http.api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that the annotated service interface method performs an HTTP GET request.
 *
 * <p>The {@link #value()} attribute specifies the relative path to be appended to the
 * client's base URL. Path variables enclosed in curly braces (e.g. {@code {id}}) are
 * resolved at call time from {@link Path @Path}-annotated parameters.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @GET("/users/{id}")
 * User getUser(@Path("id") long id);
 *
 * @GET("/users")
 * List<User> listUsers(@Query("page") int page, @Query("size") int size);
 * }</pre>
 *
 * @see POST
 * @see PUT
 * @see DELETE
 * @see PATCH
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface GET {
    /**
     * The relative URL path for this GET request.
     * May contain {@code {placeholder}} tokens resolved from {@link Path @Path} parameters.
     *
     * @return the relative path; defaults to an empty string
     */
    String value() default "";
}
