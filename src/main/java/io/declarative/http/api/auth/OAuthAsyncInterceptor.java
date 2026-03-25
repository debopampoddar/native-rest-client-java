package io.declarative.http.api.auth;

import io.declarative.http.api.interceptors.Interceptor;

import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * An interceptor that handles OAuth 2.0 token management, including transparently
 * refreshing expired tokens and retrying requests.
 *
 * @author Debopam
 */
public class OAuthAsyncInterceptor implements Interceptor {

    private final AsyncTokenManager tokenManager;

    public OAuthAsyncInterceptor(AsyncTokenManager tokenManager) {
        this.tokenManager = tokenManager;
    }

    /**
     * Intercepts the request to add the "Authorization" header. If the response is a 401 Unauthorized,
     * it attempts to refresh the token and retries the request automatically.
     *
     * @param chain the interceptor chain
     * @return a future completing with the HTTP response
     */
    @Override
    public CompletableFuture<HttpResponse<InputStream>> intercept(Chain chain) {
        HttpRequest originalRequest = chain.request();

        return tokenManager.getAccessToken().thenCompose(token -> {
            HttpRequest requestWithToken = HttpRequest.newBuilder(originalRequest, (k, v) -> true)
                    .header("Authorization", "Bearer " + token)
                    .build();

            return chain.proceed(requestWithToken).thenCompose(response -> {
                if (response.statusCode() == 401) {
                    // Token expired, try to refresh and retry
                    return tokenManager.refreshAccessToken().thenCompose(newToken -> {
                        HttpRequest retriedRequest = HttpRequest.newBuilder(originalRequest, (k, v) -> true)
                                .header("Authorization", "Bearer " + newToken)
                                .build();
                        return chain.proceed(retriedRequest);
                    });
                }
                return CompletableFuture.completedFuture(response);
            });
        });
    }
}
