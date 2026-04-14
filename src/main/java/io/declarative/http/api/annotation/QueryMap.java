package io.declarative.http.api.annotation;
import java.lang.annotation.*;

/**
 * Appends all entries of a {@link java.util.Map} as individual URI query parameters.
 *
 * <p>Entries with {@code null} values are skipped. Keys and values are percent-encoded
 * unless {@link #encoded()} is {@code true}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @GET("/reports")
 * Page<Report> getReports(@QueryMap Map<String, Object> filters);
 * }</pre>
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface QueryMap {
    /**
     * {@code true} if all keys and values are already percent-encoded.
     * @return {@code false} by default
     */
    boolean encoded() default false;
}
