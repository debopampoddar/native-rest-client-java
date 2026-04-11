package io.declarative.http.api.interceptors;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public interface HttpExchangeInterceptor {
    <T> HttpResponse<T> intercept(HttpRequest request,
                                  ExchangeChain<T> chain) throws IOException, InterruptedException;

    interface ExchangeChain<T> {
        HttpResponse<T> proceed(HttpRequest request) throws IOException, InterruptedException;
    }
}
