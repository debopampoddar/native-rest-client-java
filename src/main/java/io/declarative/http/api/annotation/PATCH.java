package io.declarative.http.api.annotation;
import java.lang.annotation.*;

/**
 * Declares that the annotated service interface method performs an HTTP PATCH request.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @PATCH("/users/{id}")
 * User patchUser(@Path("id") long id, @Body Map<String, Object> fields);
 * }</pre>
 *
 * @see PUT
 * @see Body
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PATCH {
    /** The relative URL path for this PATCH request. @return the relative path */
    String value() default "";
}
