package io.declarative.http.client;


import com.fasterxml.jackson.databind.ObjectMapper;
import io.declarative.http.error.RestClientException;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Mutable accumulator that collects URI fragments, headers, and body
 * across all parameter handlers before building the final HttpRequest.
 */
public final class RequestContext {

    private final String httpMethod;
    private String pathTemplate;
    private final List<String[]> queryParams = new ArrayList<>();
    private final Map<String, String> headers = new LinkedHashMap<>();
    private Object body;
    private final ObjectMapper objectMapper;

    public RequestContext(String httpMethod, String baseUrl,
                          String pathTemplate, ObjectMapper objectMapper) {
        this.httpMethod = httpMethod;
        this.pathTemplate = baseUrl + pathTemplate;
        this.objectMapper = objectMapper;
    }

    public void replacePath(String placeholder, String value) {
        pathTemplate = pathTemplate.replace(placeholder, value);
    }

    public void addQueryParam(String name, String value) {
        queryParams.add(new String[]{name, value});
    }

    public void addHeader(String name, String value) {
        headers.put(name, value);
    }

    public void setBody(Object body) {
        this.body = body;
    }

    /**
     * Builds the final, immutable {@link HttpRequest}.
     * URL construction uses multi-arg URI methods to prevent injection.
     */
    public HttpRequest buildRequest() {
        URI uri = buildUri();
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(uri);

        // Default headers
        builder.header("Accept", "application/json");

        // User-supplied headers
        headers.forEach(builder::header);

        // Method + body
        HttpRequest.BodyPublisher publisher = resolveBody();
        builder.method(httpMethod, publisher);

        // If body is present, set Content-Type
        if (body != null) {
            builder.header("Content-Type", "application/json; charset=UTF-8");
        }

        return builder.build();
    }

    private URI buildUri() {
        // Detect any unresolved path placeholders
        if (pathTemplate.contains("{") && pathTemplate.contains("}")) {
            throw new RestClientException(
                    "Unresolved path variable in: " + pathTemplate +
                            ". Ensure all @Path parameters are provided.");
        }

        StringBuilder sb = new StringBuilder(pathTemplate);
        if (!queryParams.isEmpty()) {
            sb.append("?");
            StringJoiner joiner = new StringJoiner("&");
            for (String[] pair : queryParams) {
                joiner.add(pair[0] + "=" + pair[1]);
            }
            sb.append(joiner);
        }

        try {
            return URI.create(sb.toString());
        } catch (IllegalArgumentException e) {
            throw new RestClientException("Invalid URI: " + sb, e);
        }
    }

    private HttpRequest.BodyPublisher resolveBody() {
        if (body == null) {
            return "GET".equalsIgnoreCase(httpMethod) || "DELETE".equalsIgnoreCase(httpMethod)
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.noBody();
        }
        try {
            byte[] json = objectMapper.writeValueAsBytes(body);
            return HttpRequest.BodyPublishers.ofByteArray(json);
        } catch (Exception e) {
            throw new RestClientException("Failed to serialize request body", e);
        }
    }
}
