package io.declarative.http.client;


import com.sun.net.httpserver.HttpServer;
import io.declarative.http.api.Body;
import io.declarative.http.api.GET;
import io.declarative.http.api.POST;
import io.declarative.http.api.Path;
import io.declarative.http.api.interceptors.Interceptor;
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
class NativeApiClientIntegrationTest {

    private static HttpServer server;
    private static String baseUrl;
    private NativeApiClient apiClient;

    public record TestUser (int id,
                            String name){
    }

    public interface TestApiService {
        @GET("/api/users/{id}")
        TestUser getUserSync(@Path("id") int id);

        @POST("/api/users")
        CompletableFuture<TestUser> createUserAsync(@Body TestUser user);
    }

    // Setup the Local HTTP Server before all tests
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
            // Verify custom interceptor header exists
            String customHeader = exchange.getRequestHeaders().getFirst("X-Test-Header");
            if (!"IntegrationTest".equals(customHeader)) {
                exchange.sendResponseHeaders(400, 0);
                exchange.close();
                return;
            }

            // Echo back a successful creation response
            String response = "{\"id\":99, \"name\":\"Bob\"}";
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

    // Setup the Client before each test
    @BeforeEach
    void setUp() {
        // We add a simple interceptor to prove the chain works and modifies requests
        Interceptor headerInterceptor = chain -> {
            var modifiedRequest = java.net.http.HttpRequest.newBuilder(chain.request(), (k, v) -> true)
                    .header("X-Test-Header", "IntegrationTest")
                    .build();
            return chain.proceed(modifiedRequest);
        };

        apiClient = new NativeApiClient.Builder()
                .baseUrl(baseUrl)
                .addInterceptor(headerInterceptor)
                .build();
    }

    @Test
    @DisplayName("Should execute SYNCHRONOUS GET request and deserialize JSON")
    void testSynchronousGet() {
        TestApiService service = apiClient.createService(TestApiService.class);

        // This call will block until the server responds
        TestUser user = service.getUserSync(1);

        assertNotNull(user);
        assertEquals(1, user.id);
        assertEquals("Alice", user.name);
    }

    @Test
    @DisplayName("Should execute ASYNCHRONOUS POST request and pass interceptor headers")
    void testAsynchronousPost() throws Exception {
        TestApiService service = apiClient.createService(TestApiService.class);
        TestUser newUser = new TestUser(0, "Bob");

        // This call returns instantly
        CompletableFuture<TestUser> futureUser = service.createUserAsync(newUser);

        // Wait for the future to complete (only needed because this is a JUnit test)
        TestUser createdUser = futureUser.get();

        assertNotNull(createdUser);
        assertEquals(99, createdUser.id);
        assertEquals("Bob", createdUser.name);
    }
}
