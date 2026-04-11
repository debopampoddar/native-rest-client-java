package io.declarative.http.api.util.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerMetricsRecorderTest {

    @Test
    void recordHttpCall_createsTimerWithTags() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry);

        recorder.recordHttpCall("GET", URI.create("http://localhost/users/123"),
                200, 1_000_000L, false);

        Timer timer = registry.find("http_client_requests").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);

        // Verify key tags
        assertThat(timer.getId().getTag("method")).isEqualTo("GET");
        assertThat(timer.getId().getTag("uri")).isEqualTo("/users/{id}");
        assertThat(timer.getId().getTag("status")).isEqualTo("200");
        assertThat(timer.getId().getTag("outcome")).isEqualTo("SUCCESS");
        assertThat(timer.getId().getTag("error")).isEqualTo("false");
    }

    @Test
    void recordIoError_usesIoErrorStatusAndOutcome() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry);

        recorder.recordHttpCall("GET", URI.create("http://localhost/users"),
                0, 2_000_000L, true);

        Timer timer = registry.find("http_client_requests").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);

        assertThat(timer.getId().getTag("status")).isEqualTo("IO_ERROR");
        assertThat(timer.getId().getTag("outcome")).isEqualTo("IO_ERROR");
        assertThat(timer.getId().getTag("error")).isEqualTo("true");
    }
}
