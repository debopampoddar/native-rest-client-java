package io.declarative.http.example;

import io.declarative.http.api.annotation.Body;
import io.declarative.http.api.annotation.DELETE;
import io.declarative.http.api.annotation.GET;
import io.declarative.http.api.annotation.POST;
import io.declarative.http.api.annotation.PUT;
import io.declarative.http.api.annotation.Path;

import java.util.concurrent.CompletableFuture;

/**
 * An example interface defining the HTTP API endpoints for a user service.
 *
 * @author Debopam
 */
public interface UserService {
    /**
     * Retrieves a user asynchronously by their unique ID via an HTTP GET request.
     *
     * @param id the unique identifier of the user to retrieve
     * @return a future completing with the requested user
     */
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

    /**
     * Retrieves a user synchronously by their unique ID via an HTTP GET request.
     *
     * @param id the unique identifier of the user to retrieve
     * @return the requested user
     */
    @GET("/users/{id}")
    User getUser(@Path("id") int id);

    /**
     * Updates an existing user via an HTTP PUT request.
     *
     * @param id          the unique identifier of the user to update
     * @param updatedUser the updated user data to be sent in the request body
     * @return the updated user
     */
    @PUT("/users/{id}")
    User updateUser(@Path("id") int id, @Body User updatedUser);

    /**
     * Deletes a user via an HTTP DELETE request.
     *
     * @param id the unique identifier of the user to delete
     * @return a response message or status string
     */
    @DELETE("/users/{id}")
    String deleteUser(@Path("id") int id);
}