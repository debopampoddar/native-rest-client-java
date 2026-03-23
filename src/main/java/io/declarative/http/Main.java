package io.declarative.http;

import io.declarative.http.client.NativeApiClient;
import io.declarative.http.example.User;
import io.declarative.http.example.UserService;

import java.net.http.HttpClient;

/**
 * The entry point for the example application demonstrating the usage of {@link NativeApiClient}.
 *
 * @author Debopam
 */
public class Main {
    /**
     * The main method that configures the HTTP client, creates the service, and executes API calls.
     *
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        // 1. Configure the native Java 21 HttpClient (e.g., adding timeouts, HTTP/2)
        HttpClient javaClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();

        // 2. Build our Retrofit replacement
        NativeApiClient apiClient = new NativeApiClient.Builder()
                .baseUrl("https://api.example.com")
                .client(javaClient)
                .build();

        // 3. Create the service
        UserService userService = apiClient.createService(UserService.class);

        // 4. Execute calls smoothly
        User user = userService.getUserById(42);
        System.out.println("Retrieved User: " + user.name());
    }
}