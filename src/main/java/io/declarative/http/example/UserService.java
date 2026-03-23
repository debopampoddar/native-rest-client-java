package io.declarative.http.example;

import io.declarative.http.api.Body;
import io.declarative.http.api.GET;
import io.declarative.http.api.POST;
import io.declarative.http.api.Path;

import java.util.concurrent.CompletableFuture;

/**
 * An example interface defining the HTTP API endpoints for a user service.
 *
 * @author Debopam
 */
public interface UserService {
    /**
     * Retrieves a user by their unique ID via an HTTP GET request.
     *
     * @param id the unique identifier of the user to retrieve
     * @return the requested user
     */
    // Asynchronous call
    @GET("/users/{id}")
    CompletableFuture<User> getUserById(@Path("id") int id);

    /**
     * Creates a new user via an HTTP POST request.
     *
     * @param newUser the user data to be sent in the request body
     * @return the created user
     */
    @POST("/users")
    User createUser(@Body User newUser);
}
