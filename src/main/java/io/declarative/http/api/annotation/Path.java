package io.declarative.http.api.annotation;
import java.lang.annotation.*;

/**
 * Binds a method parameter to a URI path variable.
 *
 * <p>The {@link #value()} must match a {@code {placeholder}} token in the path
 * defined by the HTTP verb annotation. The parameter value is percent-encoded
 * with UTF-8 by default (spaces as {@code %20}); set {@link #encoded()} to
 * {@code true} to pass the value through unchanged.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @GET("/repos/{owner}/{repo}/issues")
 * List<Issue> listIssues(@Path("owner") String owner, @Path("repo") String repo);
 * }</pre>
 *
 * <p><b>Null values are not permitted</b> — a {@code null} argument causes an
 * {@link IllegalArgumentException} at call time because an unresolved path variable
 * would produce a malformed URI.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Path {
    /** The name of the path variable to substitute; must match a {@code {token}} in the path. */
    String value();
    /**
     * {@code true} if the value is already percent-encoded and should not be encoded again.
     * @return {@code false} by default
     */
    boolean encoded() default false;
}
