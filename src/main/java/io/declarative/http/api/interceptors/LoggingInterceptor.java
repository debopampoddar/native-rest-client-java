package io.declarative.http.api.interceptors;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * An {@link Interceptor} that logs outgoing requests and incoming responses.
 * It measures and prints the execution duration.
 *
 * @author Debopam
 */
public class LoggingInterceptor implements Interceptor {
    @Override
    public CompletableFuture<HttpResponse<String>> intercept(Chain chain) {
        HttpRequest request = chain.request();
        long startTime = System.currentTimeMillis();

        System.out.println("--> [" + request.method() + "] " + request.uri());

        return chain.proceed(request).whenComplete((response, throwable) -> {
            long duration = System.currentTimeMillis() - startTime;
            if (response != null) {
                System.out.println("<-- [" + response.statusCode() + "] " + request.uri() + " (" + duration + "ms)");
            } else if (throwable != null) {
                System.out.println("<-- [ERROR] " + request.uri() + " - " + throwable.getMessage());
            }
        });
    }
}