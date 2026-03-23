package io.declarative.http.api.interceptors;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

public interface RequestExecutor {
    CompletableFuture<HttpResponse<String>> execute(HttpRequest request);
}

