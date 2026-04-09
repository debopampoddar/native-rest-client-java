package io.declarative.http.example;

import io.declarative.http.api.annotation.Body;
import io.declarative.http.api.annotation.DELETE;
import io.declarative.http.api.annotation.GET;
import io.declarative.http.api.annotation.Header;
import io.declarative.http.api.annotation.HeaderMap;
import io.declarative.http.api.annotation.POST;
import io.declarative.http.api.annotation.PUT;
import io.declarative.http.api.annotation.Path;
import io.declarative.http.api.annotation.Query;
import io.declarative.http.api.annotation.QueryMap;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * An example interface defining the HTTP API endpoints for a user service.
 *
 * @author Debopam
 */
public interface UserService {
    // Simple GET with path variable
    @GET("/users/{id}")
    User getUser(@Path("id") long id);

    // GET with query parameters
    @GET("/users")
    List<User> listUsers(
            @Query("page") int page,
            @Query("size") int size,
            @Query("sort") String sort
    );

    // GET with dynamic query map (e.g., search filters)
    @GET("/users/search")
    List<User> searchUsers(@QueryMap Map<String, Object> filters);

    // POST with body
    @POST("/users")
    User createUser(@Body User request);

    // PUT with path + body
    @PUT("/users/{id}")
    User updateUser(@Path("id") long id, @Body User request);

    // DELETE with custom header
    @DELETE("/users/{id}")
    void deleteUser(
            @Path("id") long id,
            @Header("X-Reason") String reason
    );

    // Async variant returning CompletableFuture
    @GET("/users/{id}")
    CompletableFuture<User> getUserAsync(@Path("id") long id);

    // Dynamic headers via @HeaderMap
    @GET("/users/{id}/profile")
    User getUserWithHeaders(
            @Path("id") long id,
            @HeaderMap Map<String, String> extraHeaders
    );
}