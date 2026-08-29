package io.declarative.http.api.interceptors;

import io.declarative.http.api.util.metrics.MetricsRecorder;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

public final class MetricsExchangeInterceptor
        implements HttpExchangeInterceptor, AsyncHttpExchangeInterceptor {

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

    @Override
    public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
            HttpRequest request, AsyncExchangeChain<T> chain) {
        long start = System.nanoTime();
        return chain.proceed(request).whenComplete((response, error) -> {
            int status = response == null ? 0 : response.statusCode();
            recorder.recordHttpCall(request.method(), request.uri(), status,
                    System.nanoTime() - start, error != null);
        });
    }
}
