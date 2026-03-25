package io.declarative.http.api.interceptors;

import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * A simple interceptor that logs request and response details to the console.
 * Note: This interceptor consumes the response body stream to log it and is intended for debugging.
 *
 * @author Debopam
 */
public class LoggingInterceptor implements Interceptor {
    @Override
    public CompletableFuture<HttpResponse<InputStream>> intercept(Chain chain) {
        HttpRequest request = chain.request();
        long startTime = System.currentTimeMillis();

        System.out.println("--> " + request.method() + " " + request.uri());
        request.headers().map().forEach((name, values) -> {
            System.out.println(name + ": " + String.join(", ", values));
        });
        System.out.println("--> END " + request.method());

        return chain.proceed(request).thenApply(response -> {
            long duration = System.currentTimeMillis() - startTime;
            System.out.println("<-- " + response.statusCode() + " " + response.uri() + " (" + duration + "ms)");

            response.headers().map().forEach((name, values) -> {
                System.out.println(name + ": " + String.join(", ", values));
            });

            // Note: This consumes the stream. For production, you might want a more sophisticated TeeInputStream.
            InputStream body = response.body();
            if (body != null) {
                try {
                    String bodyString = new String(body.readAllBytes());
                    System.out.println(bodyString);
                } catch (Exception e) {
                    System.out.println("[LoggingInterceptor] Failed to read response body: " + e.getMessage());
                }
            }
            System.out.println("<-- END HTTP");

            return response;
        });
    }
}
