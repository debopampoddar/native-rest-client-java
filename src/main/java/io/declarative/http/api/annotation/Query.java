package io.declarative.http.api.annotation;
import java.lang.annotation.*;

/**
 * Appends a URI query parameter to the request.
 *
 * <p>{@code null} values are silently omitted. {@link java.util.Collection} values
 * are expanded to multiple {@code name=value} pairs (e.g. {@code ?tag=java&tag=spring}).
 * Values are percent-encoded unless {@link #encoded()} is {@code true}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @GET("/search")
 * List<Result> search(@Query("q") String keyword, @Query("page") int page);
 * }</pre>
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Query {
    /** The query parameter name as it will appear in the URI. */
    String value();
    /**
     * {@code true} if the value is already percent-encoded.
     * @return {@code false} by default
     */
    boolean encoded() default false;
}
