package io.declarative.http.client;

import com.sun.net.httpserver.HttpServer;
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

/**
 * An integration test for {@link NativeApiClient} demonstrating how the API client handles
 * HTTP requests against a local HTTP server.
 *
 * @author Debopam
 */
public class NativeApiClientIntegrationTest {
    private static HttpServer server;
    private static String baseUrl;
    private NativeApiClient apiClient;

    /**
     * An internal service interface defining test endpoints.
     */
    // Define the Interface exposing both Sync and Async methods
    public interface TestApiService {
        /**
         * Simulates retrieving a user.
         *
         * @param id the user ID
         * @return the requested user
         */
        @GET("/api/users/{id}")
        CompletableFuture<User> getUser(@Path("id") int id);

        /**
         * Simulates creating a user.
         *
         * @param user the new user
         * @return the created user
         */
        @POST("/api/users")
        User createUser(@Body User user);
    }

    /**
     * Configures and starts a local HTTP server to mock API responses.
     *
     * @throws IOException if the server fails to start
     */
    // Setup the Local HTTP Server before all tests
    @BeforeAll
    static void startServer() throws IOException {
        // Bind to port 0 to let the OS pick a free port automatically
        server = HttpServer.create(new InetSocketAddress(0), 0);

        // Mock GET Endpoint
        server.createContext("/api/users/1", exchange -> {
            String response = "{\"id\":1, \"name\":\"Alice\"}";
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });

        // Mock POST Endpoint
        server.createContext("/api/users", exchange -> {
            // For integration testing, we just return the expected Bob user
            String response = "{\"id\": 2, \"name\": \"Bob\"}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            // Use 201 Created for POST or 200 OK; NativeApiClient expects 2xx
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });

        server.setExecutor(null); // creates a default executor
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    /**
     * Stops the local HTTP server after all tests have run.
     */
    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    /**
     * Initializes a new instance of {@link NativeApiClient} configured to point at the local server.
     */
    // Setup the Client before each test
    @BeforeEach
    void setUp() {
        apiClient = new NativeApiClient.Builder()
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * Validates that the client successfully sends a GET request and deserializes the response.
     */
    @Test
    @DisplayName("Test GET request and deserialize JSON")
    void testGet() {
        TestApiService service = apiClient.createService(TestApiService.class);

        // This call will block until the server responds
        User user = service.getUser(1).join();

        assertNotNull(user);
        assertEquals(1, user.id());
        assertEquals("Alice", user.name());
    }

    /**
     * Validates that the client successfully sends a POST request and deserializes the response.
     */
    @Test
    @DisplayName("Test POST request and deserialize JSON")
    void testPost() {
        TestApiService service = apiClient.createService(TestApiService.class);

        // This call will block until the server responds
        User user = service.createUser(new User(2, "Bob"));

        assertNotNull(user);
        assertEquals(2, user.id());
        assertEquals("Bob", user.name());
    }
}
