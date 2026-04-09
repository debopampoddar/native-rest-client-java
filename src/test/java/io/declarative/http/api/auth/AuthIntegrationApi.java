package io.declarative.http.api.auth;

import io.declarative.http.api.annotation.GET;

public interface AuthIntegrationApi {

    @GET("/basic-protected")
    String basicProtected();

    @GET("/oauth-protected")
    String oauthProtected();

    @GET("/oauth-refreshed")
    String oauthRefreshed();
}
