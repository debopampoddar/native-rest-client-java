package io.declarative.http.example;

import io.declarative.http.api.Body;
import io.declarative.http.api.GET;
import io.declarative.http.api.POST;
import io.declarative.http.api.Path;

public interface UserService {
    @GET("/users/{id}")
    User getUserById(@Path("id") int id);

    @POST("/users")
    User createUser(@Body User newUser);
}
