package io.declarative.http.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a parameter to one named part of a {@link Multipart} request.
 *
 * <p>String and other scalar values are emitted as UTF-8 text parts. Use
 * {@link io.declarative.http.client.MultipartPart} for binary data, a filename,
 * or a non-default content type. A {@code null} value omits the part.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Part {

    /**
     * Returns the multipart form field name.
     *
     * @return a non-blank multipart field name
     */
    String value();
}
