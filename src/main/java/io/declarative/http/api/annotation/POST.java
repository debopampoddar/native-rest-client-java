package io.declarative.http.api.annotation;
import java.lang.annotation.*;

/**
 * Declares that the annotated service interface method performs an HTTP POST request.
 *
 * <p>POST methods typically carry a request body serialised via
 * {@link Body @Body} or form fields via {@link Field @Field} + {@link FormUrlEncoded}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @POST("/users")
 * User createUser(@Body User user);
 * }</pre>
 *
 * @see GET
 * @see Body
 * @see FormUrlEncoded
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface POST {
    /** The relative URL path for this POST request. @return the relative path */
    String value() default "";
}
