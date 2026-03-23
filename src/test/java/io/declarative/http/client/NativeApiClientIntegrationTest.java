package io.declarative.http.client;

import com.sun.net.httpserver.HttpServer;
import io.declarative.http.api.Body;
import io.declarative.http.api.GET;
import io.declarative.http.api.POST;
import io.declarative.http.api.Query;
import io.declarative.http.api.auth.AsyncTokenManager;
import io.declarative.http.api.auth.BasicAuthInterceptor;
import io.declarative.http.api.auth.OAuthAsyncInterceptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeApiClientIntegrationTest {

    private static HttpServer server;
    private static String baseUrl;

    public record TestData(String message) {
    }

    // --- API Interface ---
    public interface IntegrationService {
        @GET("/api/public")
        TestData getPublicData(@Query("search") String searchTerm);

        @GET("/api/public/error")
        TestData getPublicError();

        @GET("/api/basic")
        TestData getBasicSecuredData();

        @POST("/api/oauth")
        CompletableFuture<TestData> postOAuthDataAsync(@Body TestData data);
    }

    // --- Local Server Mock Setup ---
    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);

        // 1. No Auth Endpoint (Happy & Error)
        server.createContext("/api/public", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (exchange.getRequestURI().getPath().endsWith("/error")) {
                sendResponse(exchange, 500, "{\"error\": \"Internal Server Error\"}");
            } else {
                sendResponse(exchange, 200, "{\"message\": \"Search result for: " + query + "\"}");
            }
        });

        // 2. Basic Auth Endpoint
        server.createContext("/api/basic", exchange -> {
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            if ("Basic YWRtaW46cGFzc3dvcmQxMjM=".equals(authHeader)) { // "admin:password123" Base64'd
                sendResponse(exchange, 200, "{\"message\": \"Basic Auth Success\"}");
            } else {
                sendResponse(exchange, 401, "{\"error\": \"Unauthorized Basic\"}");
            }
        });

        // 3. OAuth Endpoint (Simulating token expiration)
        server.createContext("/api/oauth", exchange -> {
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            if ("Bearer fresh_token_999".equals(authHeader)) {
                sendResponse(exchange, 201, "{\"message\": \"OAuth Success\"}");
            } else {
                sendResponse(exchange, 401, "{\"error\": \"Token Expired\"}");
            }
        });

        server.setExecutor(null);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static void sendResponse(com.sun.net.httpserver.HttpExchange exchange, int code, String body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, body.length());
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("NoAuth: Happy Path with @Query Parameter")
    void testNoAuth_HappyPath() {
        IntegrationService api = new NativeApiClient.Builder().baseUrl(baseUrl).build().createService(IntegrationService.class);

        TestData response = api.getPublicData("java21");

        assertEquals("Search result for: search=java21", response.message);
    }

    @Test
    @DisplayName("NoAuth: Error Path (500 Server Error)")
    void testNoAuth_ErrorPath() {
        IntegrationService api = new NativeApiClient.Builder()
                .baseUrl(baseUrl)
                .build()
                .createService(IntegrationService.class);

        RuntimeException exception = assertThrows(RuntimeException.class, api::getPublicError);
        assertTrue(exception.getMessage().contains("API Call failed: 500"), exception.getMessage());
    }

    @Test
    @DisplayName("BasicAuth: Happy Path with Correct Credentials")
    void testBasicAuth_HappyPath() {
        NativeApiClient client = new NativeApiClient.Builder()
                .baseUrl(baseUrl)
                .addInterceptor(new BasicAuthInterceptor("admin", "password123"))
                .build();
        IntegrationService api = client.createService(IntegrationService.class);

        TestData response = api.getBasicSecuredData();
        assertEquals("Basic Auth Success", response.message);
    }

    @Test
    @DisplayName("BasicAuth: Error Path with Wrong Credentials")
    void testBasicAuth_ErrorPath() {
        NativeApiClient client = new NativeApiClient.Builder()
                .baseUrl(baseUrl)
                .addInterceptor(new BasicAuthInterceptor("wrongUser", "wrongPass"))
                .build();
        IntegrationService api = client.createService(IntegrationService.class);

        RuntimeException exception = assertThrows(RuntimeException.class, api::getBasicSecuredData);
        assertTrue(exception.getMessage().contains("API Call failed: 401"), exception.getMessage());
    }

    @Test
    @DisplayName("OAuth: Happy Path with Auto-Refresh on 401")
    void testOAuth_HappyPath_WithRefresh() throws Exception {
        // Mock token manager starts with expired token
        AsyncTokenManager tokenManager = new AsyncTokenManager() {
            private String currentToken = "expired_token";

            @Override
            public CompletableFuture<String> getAccessToken() {
                return CompletableFuture.completedFuture(currentToken);
            }

            @Override
            public CompletableFuture<String> refreshAccessToken() {
                // Simulate network refresh
                this.currentToken = "fresh_token_999";
                return CompletableFuture.completedFuture(currentToken);
            }
        };

        NativeApiClient client = new NativeApiClient.Builder()
                .baseUrl(baseUrl)
                .addInterceptor(new OAuthAsyncInterceptor(tokenManager))
                .build();
        IntegrationService api = client.createService(IntegrationService.class);

        // This will send 'expired', get 401, auto-refresh to 'fresh_token_999', retry, and get 201
        CompletableFuture<TestData> futureResponse = api.postOAuthDataAsync(new TestData("Payload"));

        assertEquals("OAuth Success", futureResponse.get().message);
    }
}
