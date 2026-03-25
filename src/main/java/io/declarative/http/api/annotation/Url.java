package io.declarative.http.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Overrides the base URL and path for a specific request.
 * <p>
 * The value of the annotated parameter will be used as the full URL for the request,
 * completely bypassing the base URL set in the client builder.
 *
 * <pre><code>
 * {@literal @}GET
 * String downloadFile(@Url String fileUrl);
 * </code></pre>
 *
 * @author Debopam
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Url {
}
