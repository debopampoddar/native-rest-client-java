package io.declarative.http.api.annotation;
import java.lang.annotation.*;

/**
 * Declares that the annotated service interface method performs an HTTP DELETE request.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @DELETE("/users/{id}")
 * void deleteUser(@Path("id") long id);
 *
 * // With envelope to inspect the status code:
 * @DELETE("/users/{id}")
 * HttpResponseEnvelope<Void> deleteUser(@Path("id") long id);
 * }</pre>
 *
 * @see GET
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DELETE {
    /** The relative URL path for this DELETE request. @return the relative path */
    String value() default "";
}
