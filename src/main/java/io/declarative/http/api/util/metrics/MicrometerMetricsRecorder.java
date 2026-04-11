package io.declarative.http.api.util.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.net.URI;
import java.time.Duration;

public final class MicrometerMetricsRecorder implements MetricsRecorder {

    private final MeterRegistry registry;
    private final String metricName;

    public MicrometerMetricsRecorder(MeterRegistry registry) {
        this(registry, "http_client_requests");
    }

    public MicrometerMetricsRecorder(MeterRegistry registry, String metricName) {
        this.registry = registry;
        this.metricName = metricName;
    }

    @Override
    public void recordHttpCall(String method,
                               URI uri,
                               int status,
                               long durationNanos,
                               boolean error) {

        // Normalize URI to avoid high-cardinality labels
        String normalizedUri = normalizeUri(uri.getPath());
        String statusTag = status == 0 ? "IO_ERROR" : String.valueOf(status);
        String outcome = outcome(status);

        Timer.builder(metricName)
                .description("HTTP client request duration")
                .tag("method", method)
                .tag("uri", normalizedUri)
                .tag("status", statusTag)
                .tag("outcome", outcome)
                .tag("error", String.valueOf(error))
                .register(registry)
                .record(Duration.ofNanos(durationNanos));
    }

    private String outcome(int status) {
        if (status == 0) return "IO_ERROR";
        if (status < 200) return "INFORMATIONAL";
        if (status < 300) return "SUCCESS";
        if (status < 400) return "REDIRECTION";
        if (status < 500) return "CLIENT_ERROR";
        return "SERVER_ERROR";
    }

    // Very simple example; you can plug in your routing metadata to avoid regexing paths
    private String normalizeUri(String path) {
        if (path == null || path.isEmpty()) return "/";
        // /users/123 -> /users/{id}
        return path.replaceAll("/\\d+", "/{id}");
    }
}
