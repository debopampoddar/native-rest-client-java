package io.declarative.http.client;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.declarative.http.api.Body;
import io.declarative.http.api.GET;
import io.declarative.http.api.POST;
import io.declarative.http.api.Path;
import io.declarative.http.example.User;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class NativeApiClientIntegrationTest {
    private static HttpServer server;
    private static String baseUrl;
    private NativeApiClient apiClient;

    // 2. Define the Interface exposing both Sync and Async methods
    public interface TestApiService {
        @GET("/api/users/{id}")
        User getUser(@Path("id") int id);

        @POST("/api/users")
        User user(@Body User user);
    }

    // 3. Setup the Local HTTP Server before all tests
    @BeforeAll
    static void startServer() throws IOException {
        // Bind to port 0 to let the OS pick a free port automatically
        server = HttpServer.create(new InetSocketAddress(0), 0);

        // Mock GET Endpoint
        server.createContext("/api/users/1", exchange -> {
            String response = "{\"id\":1, \"name\":\"Alice\"}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
        });

        // Mock POST Endpoint
        server.createContext("/api/users", exchange -> {
            // Echo back a successful creation response
            String response = "{\"id\": 2, \"name\": \"Bob\"}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
        });

        server.setExecutor(null); // creates a default executor
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    // 4. Setup the Client before each test
    @BeforeEach
    void setUp() {
        apiClient = new NativeApiClient.Builder()
                .baseUrl(baseUrl)
                .build();
    }

    @Test
    @DisplayName("Test SYNCHRONOUS GET request and deserialize JSON")
    void testSynchronousGet() {
        TestApiService service = apiClient.createService(TestApiService.class);

        // This call will block until the server responds
        User user = service.getUser(1);

        assertNotNull(user);
        assertEquals(1, user.id());
        assertEquals("Alice", user.name());
    }

    @Test
    @DisplayName("Test SYNCHRONOUS POST request and deserialize JSON")
    void testGetJsonArray() {
        TestApiService service = apiClient.createService(TestApiService.class);

        // This call will block until the server responds
        User user = service.user(new User(2, "Bob"));

        assertNotNull(user);
        assertEquals(2, user.id());
        assertEquals("Bob", user.name());
    }
}
