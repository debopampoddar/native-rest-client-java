package io.declarative.http.client;

import com.sun.net.httpserver.HttpServer;
import io.declarative.http.api.annotation.Body;
import io.declarative.http.api.annotation.DELETE;
import io.declarative.http.api.annotation.GET;
import io.declarative.http.api.annotation.Header;
import io.declarative.http.api.annotation.Headers;
import io.declarative.http.api.annotation.POST;
import io.declarative.http.api.annotation.PUT;
import io.declarative.http.api.annotation.Path;
import io.declarative.http.api.annotation.Query;
import io.declarative.http.api.annotation.Url;
import io.declarative.http.api.auth.AsyncTokenManager;
import io.declarative.http.api.auth.BasicAuthInterceptor;
import io.declarative.http.api.auth.OAuthAsyncInterceptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration tests for the {@link NativeApiClient}.
 * <p>
 * These tests use a lightweight, in-memory {@link HttpServer} to mock a real
 * REST API. This allows for testing the full HTTP request/response lifecycle,
 * including serialization, deserialization, authentication, and error handling.
 *
 * @author Debopam
 */
class NativeApiClientIntegrationTest {

    private static HttpServer server;
    private static String baseUrl;

    /**
     * A simple record used for serializing and deserializing JSON payloads in tests.
     */
    public record TestData(String message) {
    }

    /**
     * Defines a standard set of API endpoints for integration testing,
     * covering common HTTP methods and authentication schemes.
     */
    public interface IntegrationService {
        /**
         * Tests a simple GET request with a query parameter.
         */
        @GET("/api/public")
        TestData getPublicData(@Query("search") String searchTerm);

        /**
         * Tests the client's ability to handle server-side errors (HTTP 500).
         */
        @GET("/api/public/error")
        TestData getPublicError();

        /**
         * Tests an endpoint protected by Basic Authentication.
         */
        @GET("/api/basic")
        TestData getBasicSecuredData();

        /**
         * Tests an asynchronous POST request to an OAuth-protected endpoint.
         */
        @POST("/api/oauth")
        CompletableFuture<TestData> postOAuthDataAsync(@Body TestData data);

        /**
         * Tests a PUT request with a path parameter and a request body.
         */
        @PUT("/api/data/{id}")
        TestData updateData(@Path("id") int id, @Body TestData data);

        /**
         * Tests a DELETE request with a path parameter, expecting a raw String response.
         */
        @DELETE("/api/data/{id}")
        String deleteData(@Path("id") int id);

        /**
         * Tests the combination of static headers from {@link Headers}
         * and dynamic headers from the {@link Header} parameter annotation.
         */
        @Headers({
                "Accept: application/json",
                "User-Agent: NativeRestClient/1.0"
        })
        @GET("/api/secure")
        TestData getSecureData(@Header("Authorization") String token);

        /**
         * Tests the ability to override the base URL at request time using the {@link Url} annotation.
         */
        @GET
        TestData getDataFromDynamicUrl(@Url String fullUrl);

        /**
         * Tests streaming a large file download directly into an {@link InputStream}
         * to avoid loading the entire response body into memory.
         */
        @GET
        InputStream downloadLargeFile(@Url String fullUrl);
    }

    // --- Local Server Mock Setup ---
    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);

        // No Auth Endpoint (Happy & Error)
        server.createContext("/api/public", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (exchange.getRequestURI().getPath().endsWith("/error")) {
                sendResponse(exchange, 500, "{\"error\": \"Internal Server Error\"}");
            } else {
                sendResponse(exchange, 200, "{\"message\": \"Search result for: " + query + "\"}");
            }
        });

        // Basic Auth Endpoint
        server.createContext("/api/basic", exchange -> {
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            if ("Basic YWRtaW46cGFzc3dvcmQxMjM=".equals(authHeader)) { // "admin:password123" Base64'd
                sendResponse(exchange, 200, "{\"message\": \"Basic Auth Success\"}");
            } else {
                sendResponse(exchange, 401, "{\"error\": \"Unauthorized Basic\"}");
            }
        });

        // OAuth Endpoint (Simulating token expiration)
        server.createContext("/api/oauth", exchange -> {
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            if ("Bearer fresh_token_999".equals(authHeader)) {
                sendResponse(exchange, 201, "{\"message\": \"OAuth Success\"}");
            } else {
                sendResponse(exchange, 401, "{\"error\": \"Token Expired\"}");
            }
        });

        server.createContext("/api/data/", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            // Extract the ID from the path (e.g., /api/data/42 -> 42)
            String id = path.substring(path.lastIndexOf('/') + 1);

            if ("PUT".equalsIgnoreCase(method)) {
                sendResponse(exchange, 200, "{\"message\": \"Updated item " + id + "\"}");
            } else if ("DELETE".equalsIgnoreCase(method)) {
                sendResponse(exchange, 204, ""); // 204 No Content is standard for DELETE
            } else {
                sendResponse(exchange, 405, "{\"message\": \"Method Not Allowed\"}");
            }
        });

        // Headers check
        server.createContext("/api/secure", exchange -> {
            String accept = exchange.getRequestHeaders().getFirst("Accept");
            String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
            String auth = exchange.getRequestHeaders().getFirst("Authorization");

            if ("application/json".equals(accept) && "NativeRestClient/1.0".equals(userAgent) && "Bearer Token123".equals(auth)) {
                sendResponse(exchange, 200, "{\"message\": \"Headers are perfect\"}");
            } else {
                sendResponse(exchange, 400, "{\"message\": \"Missing or invalid headers\"}");
            }
        });

        // Dynamic URL target
        server.createContext("/external/data", exchange -> {
            sendResponse(exchange, 200, "{\"message\": \"Reached dynamic endpoint\"}");
        });

        // Binary Stream Target (Simulating a file download)
        server.createContext("/cdn/large-file.bin", exchange -> {
            byte[] fakeFileBytes = new byte[]{ 0x01, 0x02, 0x03, 0x04, 0x05 };
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, fakeFileBytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(fakeFileBytes); }
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
        assertTrue(exception.getMessage().contains("HTTP 500"), exception.getMessage());
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
        assertTrue(exception.getMessage().contains("HTTP 401"), exception.getMessage());
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

    @Test
    @DisplayName("PUT: Happy Path mapping @Path and @Body")
    void testPut_HappyPath() {
        IntegrationService api = new NativeApiClient.Builder().baseUrl(baseUrl).build().createService(IntegrationService.class);

        TestData updatePayload = new TestData("Updated Data");
        TestData response = api.updateData(42, updatePayload);

        assertEquals("Updated item 42", response.message);
    }

    @Test
    @DisplayName("DELETE: Happy Path mapping @Path and returning raw String")
    void testDelete_HappyPath() {
        IntegrationService api = new NativeApiClient.Builder().baseUrl(baseUrl).build().createService(IntegrationService.class);

        // The server returns a 204 No Content with an empty body, which our client parses into an empty String.
        String response = api.deleteData(99);

        assertTrue(response.isEmpty(), "DELETE response should be empty for 204 No Content");
    }

    @Test
    @DisplayName("Should successfully send both @Headers and @Header annotations")
    void testDynamicAndStaticHeaders() {
        IntegrationService api = new NativeApiClient.Builder().baseUrl(baseUrl).build().createService(IntegrationService.class);

        TestData response = api.getSecureData("Bearer Token123");
        assertEquals("Headers are perfect", response.message);
    }

    @Test
    @DisplayName("Should route directly to a dynamic @Url, completely bypassing the baseUrl")
    void testDynamicUrlOverride() {
        // We initialize the client with a DUMMY base URL. It should never be hit.
        IntegrationService api = new NativeApiClient.Builder().baseUrl("http://invalid-domain.com").build().createService(IntegrationService.class);

        String dynamicTarget = baseUrl + "/external/data";
        TestData response = api.getDataFromDynamicUrl(dynamicTarget);

        assertEquals("Reached dynamic endpoint", response.message);
    }

    @Test
    @DisplayName("Should return raw InputStream for large file downloads to prevent OOM")
    void testLargeFileStreaming() throws IOException {
        IntegrationService api = new NativeApiClient.Builder().baseUrl(baseUrl).build().createService(IntegrationService.class);

        String dynamicTarget = baseUrl + "/cdn/large-file.bin";

        // This executes the request but DOES NOT load the body into memory.
        try (InputStream stream = api.downloadLargeFile(dynamicTarget)) {
            assertNotNull(stream);

            // Read bytes iteratively (just like you would saving to a file)
            byte[] result = stream.readAllBytes();
            assertEquals(5, result.length);
            assertEquals(0x01, result[0]);
            assertEquals(0x05, result[4]);
        }
    }
}
