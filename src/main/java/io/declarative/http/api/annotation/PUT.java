package io.declarative.http.api.annotation;
import java.lang.annotation.*;

/**
 * Declares that the annotated service interface method performs an HTTP PUT request.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @PUT("/users/{id}")
 * User updateUser(@Path("id") long id, @Body User user);
 * }</pre>
 *
 * @see GET
 * @see Body
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PUT {
    /** The relative URL path for this PUT request. @return the relative path */
    String value() default "";
}
