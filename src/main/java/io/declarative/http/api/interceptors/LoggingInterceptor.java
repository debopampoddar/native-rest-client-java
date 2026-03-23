package io.declarative.http.api.interceptors;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * An {@link Interceptor} that logs outgoing requests and incoming responses.
 * It measures and prints the execution duration, request headers, and the raw response body.
 *
 * @author Debopam
 */
public class LoggingInterceptor implements Interceptor {
    @Override
    public CompletableFuture<HttpResponse<String>> intercept(Chain chain) {
        HttpRequest request = chain.request();
        long startTime = System.currentTimeMillis();

        System.out.println("--> " + request.method() + " " + request.uri());
        request.headers().map().forEach((name, values) -> {
            System.out.println(name + ": " + String.join(", ", values));
        });
        
        if (request.bodyPublisher().isPresent()) {
            System.out.println("--> (Request body is present but natively omitted from logs)");
        }
        System.out.println("--> END " + request.method());

        return chain.proceed(request).whenComplete((response, throwable) -> {
            long duration = System.currentTimeMillis() - startTime;
            if (response != null) {
                System.out.println("<-- " + response.statusCode() + " " + request.uri() + " (" + duration + "ms)");
                response.headers().map().forEach((name, values) -> {
                    System.out.println(name + ": " + String.join(", ", values));
                });
                
                String body = response.body();
                if (body != null && !body.isEmpty()) {
                    System.out.println(body);
                }
                System.out.println("<-- END HTTP");
            } else if (throwable != null) {
                System.out.println("<-- ERROR " + request.uri() + " - " + throwable.getMessage());
            }
        });
    }
}