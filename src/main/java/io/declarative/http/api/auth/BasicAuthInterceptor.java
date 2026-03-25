package io.declarative.http.api.auth;

import io.declarative.http.api.interceptors.Interceptor;

import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/**
 * An interceptor that automatically adds a "Authorization: Basic ..." header to every request.
 *
 * @author Debopam
 */
public class BasicAuthInterceptor implements Interceptor {

    private final String credentials;

    /**
     * Creates a new Basic Authentication interceptor.
     *
     * @param username the username
     * @param password the password
     */
    public BasicAuthInterceptor(String username, String password) {
        String authString = username + ":" + password;
        this.credentials = "Basic " + Base64.getEncoder().encodeToString(authString.getBytes());
    }

    /**
     * Adds the "Authorization" header and proceeds with the request.
     *
     * @param chain the interceptor chain
     * @return a future completing with the HTTP response
     */
    @Override
    public CompletableFuture<HttpResponse<InputStream>> intercept(Chain chain) {
        HttpRequest originalRequest = chain.request();
        // Java 16+ allows copying an existing request easily
        HttpRequest authenticatedRequest = HttpRequest.newBuilder(originalRequest, (k, v) -> true)
                .header("Authorization", credentials)
                .build();
        return chain.proceed(authenticatedRequest);
    }
}
