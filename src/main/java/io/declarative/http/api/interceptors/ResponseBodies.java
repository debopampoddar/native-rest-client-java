package io.declarative.http.api.interceptors;

import java.io.IOException;
import java.net.http.HttpResponse;

/** Internal ownership helper for responses discarded by an interceptor. */
final class ResponseBodies {

    private ResponseBodies() {
    }

    /** Closes a closeable response body, allowing the transport to release resources. */
    static void close(HttpResponse<?> response) throws IOException {
        Object body = response.body();
        if (body instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Failed to close discarded response body", e);
            }
        }
    }
}
