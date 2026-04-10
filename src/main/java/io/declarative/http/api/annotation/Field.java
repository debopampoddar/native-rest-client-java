package io.declarative.http.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Denotes a single named form field for {@link FormUrlEncoded} requests.
 *
 * <p>Each annotated parameter is URL-encoded and appended to the request body as
 * {@code application/x-www-form-urlencoded} in the format {@code name=value}.
 *
 * <p><b>Usage — must be paired with {@link FormUrlEncoded} on the method:</b>
 * <pre>{@code
 * @FormUrlEncoded
 * @POST("/auth/token")
 * String login(@Field("username") String username,
 *              @Field("password") String password);
 * }</pre>
 *
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Field {

    /**
     * The form field name sent in the request body.
     */
    String value();

    /**
     * If {@code true} the value is assumed to already be URL-encoded and is
     * sent as-is. If {@code false} (default) the value is percent-encoded
     * using UTF-8 before being written to the body.
     */
    boolean encoded() default false;
}
