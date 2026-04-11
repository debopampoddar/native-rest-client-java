package io.declarative.http.api.interceptors;

import io.declarative.http.api.util.metrics.MetricsRecorder;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public final class MetricsExchangeInterceptor implements HttpExchangeInterceptor {

    private final MetricsRecorder recorder;

    public MetricsExchangeInterceptor(MetricsRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public <T> HttpResponse<T> intercept(HttpRequest request,
                                         ExchangeChain<T> chain)
            throws IOException, InterruptedException {
        long start = System.nanoTime();
        boolean error = false;
        HttpResponse<T> response = null;

        try {
            response = chain.proceed(request);
            return response;
        } catch (IOException | InterruptedException e) {
            error = true;
            throw e;
        } finally {
            long duration = System.nanoTime() - start;
            int status = (response != null ? response.statusCode() : 0);
            recorder.recordHttpCall(request.method(), request.uri(), status, duration, error);
        }
    }
}
