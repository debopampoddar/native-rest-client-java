package io.declarative.http.api.util.metrics;

import java.net.URI;

public interface MetricsRecorder {

    void recordHttpCall(String method,
                        URI uri,
                        int status,
                        long durationNanos,
                        boolean error);
}
