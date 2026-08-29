package io.declarative.http.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Binary metadata for a {@code @Part} parameter in a multipart request.
 *
 * <p>The part is held in memory so it is intended for ordinary API uploads. For
 * very large uploads, use a dedicated streaming endpoint until streaming multipart
 * publishing is introduced.
 */
public final class MultipartPart {

    private final byte[] bytes;
    private final String fileName;
    private final String contentType;

    /**
     * Creates a part after validating metadata and defensively copying bytes.
     *
     * @param bytes binary payload
     * @param fileName optional filename presented to the server
     * @param contentType non-blank MIME type
     */
    private MultipartPart(byte[] bytes, String fileName, String contentType) {
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
        this.fileName = fileName;
        this.contentType = requireHeaderSafe(contentType, "contentType");
        if (fileName != null) {
            requireHeaderSafe(fileName, "fileName");
        }
    }

    /**
     * Creates an in-memory binary part.
     *
     * @param bytes payload bytes
     * @param fileName optional filename
     * @param contentType MIME type such as {@code image/png}
     * @return a multipart part
     */
    public static MultipartPart ofBytes(byte[] bytes, String fileName, String contentType) {
        return new MultipartPart(bytes, fileName, contentType);
    }

    /**
     * Reads a file into an in-memory multipart part.
     *
     * @param file source file
     * @param contentType MIME type such as {@code application/pdf}
     * @return a multipart part with the file's name
     * @throws IOException if the file cannot be read
     */
    public static MultipartPart fromFile(Path file, String contentType) throws IOException {
        Objects.requireNonNull(file, "file");
        Path name = file.getFileName();
        if (name == null) {
            throw new IllegalArgumentException("file must have a filename");
        }
        return new MultipartPart(Files.readAllBytes(file), name.toString(), contentType);
    }

    /**
     * Returns a defensive copy of the payload.
     *
     * @return payload bytes
     */
    public byte[] bytes() {
        return bytes.clone();
    }

    /**
     * Returns the optional transmitted filename.
     *
     * @return filename, or {@code null}
     */
    public String fileName() {
        return fileName;
    }

    /**
     * Returns the MIME type for this part.
     *
     * @return non-blank MIME type
     */
    public String contentType() {
        return contentType;
    }

    /**
     * Validates that a value is safe to embed in a MIME header.
     *
     * @param value header value
     * @param name parameter name for diagnostics
     * @return validated value
     */
    private static String requireHeaderSafe(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " must not contain CR or LF");
        }
        return value;
    }
}
