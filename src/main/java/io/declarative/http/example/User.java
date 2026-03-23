package io.declarative.http.example;

/**
 * A record representing a user in the example application.
 *
 * @param id   the unique identifier of the user
 * @param name the name of the user
 * @author Debopam
 */
public record User(int id,
                   String name) {
}
