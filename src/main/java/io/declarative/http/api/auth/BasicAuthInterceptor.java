package io.declarative.http.api.auth;

import io.declarative.http.api.interceptors.ClientInterceptor;
import io.declarative.http.api.interceptors.InterceptorChain;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Adds HTTP Basic Authentication credentials to every request.
 *
 * Header format:
 *   Authorization: Basic base64(username:password)
 *
 * See RFC 7617 for details on the Basic scheme.
 */
public final class BasicAuthInterceptor implements ClientInterceptor {

    private final Supplier<String> usernameSupplier;
    private final Supplier<String> passwordSupplier;

    public BasicAuthInterceptor(Supplier<String> usernameSupplier,
                                Supplier<String> passwordSupplier) {
        this.usernameSupplier = Objects.requireNonNull(usernameSupplier, "usernameSupplier");
        this.passwordSupplier = Objects.requireNonNull(passwordSupplier, "passwordSupplier");
    }

    public BasicAuthInterceptor(String username,
                                String password) {
        this.usernameSupplier = () -> Objects.requireNonNull(username, "usernameSupplier");
        this.passwordSupplier = () -> Objects.requireNonNull(password, "passwordSupplier");
    }

    @Override
    public HttpRequest intercept(HttpRequest request, InterceptorChain chain) throws IOException {
        String username = usernameSupplier.get();
        String password = passwordSupplier.get();

        if (username == null || password == null) {
            // If credentials are missing, we pass the request through unchanged.
            return chain.proceed(request);
        }

        String userPass = username + ":" + password;
        String encoded = Base64.getEncoder()
                .encodeToString(userPass.getBytes(StandardCharsets.UTF_8));

        HttpRequest authenticated = HttpRequest.newBuilder(request, (k, v) -> true)
                .header("Authorization", "Basic " + encoded)
                .build();

        return chain.proceed(authenticated);
    }
}
