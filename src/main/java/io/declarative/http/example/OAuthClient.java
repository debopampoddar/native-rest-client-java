package io.declarative.http.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.declarative.http.api.auth.oauth.AccessToken;
import io.declarative.http.api.auth.oauth.OAuthInterceptor;
import io.declarative.http.api.auth.oauth.RefreshingTokenManager;
import io.declarative.http.api.auth.oauth.TokenFetcher;
import io.declarative.http.api.auth.oauth.TokenManager;
import io.declarative.http.client.NativeRestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public final class OAuthClient {

    public static NativeRestClient createClientWithOAuth() {
        HttpClient httpClient = HttpClient.newHttpClient();
        ObjectMapper mapper = new ObjectMapper();

        // These would typically come from secure config / secrets manager
        String tokenEndpoint = "https://auth.example.com/oauth/token";
        String clientId = "your-client-id";
        String clientSecret = "your-client-secret";
        String refreshToken = "initial-refresh-token-from-oauth-flow";

        TokenFetcher fetcher = () -> {
            try {
                String bodyJson = """
                    {
                      "grant_type": "refresh_token",
                      "client_id": "%s",
                      "client_secret": "%s",
                      "refresh_token": "%s"
                    }
                    """.formatted(clientId, clientSecret, refreshToken);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(tokenEndpoint))
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                        .build();

                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 400) {
                    throw new IllegalStateException(
                            "Token endpoint error " + response.statusCode() + ": " + response.body());
                }

                JsonNode json = mapper.readTree(response.body());
                String accessTokenValue = json.get("access_token").asText();
                long expiresInSeconds = json.get("expires_in").asLong();

                // Some providers also return a new refresh_token; update your stored one if so.
                if (json.has("refresh_token")) {
                    // refreshToken = json.get("refresh_token").asText();
                    // persist updated refresh token securely
                }

                Instant expiresAt = Instant.now().plusSeconds(expiresInSeconds);

                return new AccessToken(accessTokenValue, expiresAt);

            } catch (Exception e) {
                throw new IllegalStateException("Failed to refresh OAuth token", e);
            }
        };

        // Proactively refresh tokens 60 seconds before expiry, check every 30 seconds
        TokenManager tokenManager = new RefreshingTokenManager(
                fetcher,
                Duration.ofSeconds(60),
                null
        );

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        TokenManager tokens = new RefreshingTokenManager(
                fetcher::fetchNewToken,   // TokenFetcher
                Duration.ofSeconds(30),           // refresh before expiry  ← FIX: was mislabelled
                scheduler                         // ← this is the ScheduledExecutorService
        );

        return NativeRestClient
                .builder("https://api.example.com")
                .addInterceptor(new OAuthInterceptor(tokens::getAccessToken))
                .build();
    }
}
