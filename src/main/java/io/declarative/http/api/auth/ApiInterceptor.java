package io.declarative.http.api.auth;

import io.declarative.http.api.interceptors.RequestExecutor;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

public interface ApiInterceptor {
    CompletableFuture<HttpResponse<String>> intercept(HttpRequest request, RequestExecutor chain);
}
