package io.declarative.http.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.declarative.http.error.RestClientException;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Mutable accumulator that collects URI fragments, headers, query parameters,
 * form fields, and body across all {@link io.declarative.http.handler.ParameterHandler}
 * instances before producing the final immutable {@link HttpRequest}.
 *
 * <p>Lifecycle (managed by {@code InvocationDispatcher.buildRequest}):
 * <ol>
 *   <li>Constructed with the HTTP method, base URL, and path template.</li>
 *   <li>Each parameter handler calls one mutating method
 *       ({@code replacePath}, {@code addQueryParam}, {@code addHeader},
 *       {@code setBody}, {@code addFormField}, {@code overrideFullUrl}).</li>
 *   <li>{@link #buildRequest()} is called once to produce the immutable request.</li>
 * </ol>
 */
public final class RequestContext {

    // ── Core request state ────────────────────────────────────────────────────

    private final String httpMethod;

    /**
     * Starts as {@code baseUrl + pathTemplate}.
     * Can be completely replaced by {@link #overrideFullUrl(String)} when
     * a method parameter is annotated with {@code @Url}.
     */
    private String resolvedUrl;

    private final List<String[]>        queryParams = new ArrayList<>();
    private final Map<String, String>   headers     = new LinkedHashMap<>();
    private Object                      body;
    private final ObjectMapper          objectMapper;

    // ── Form-encoding state ───────────────────────────────────────────────────

    /**
     * Accumulated key=value pairs for {@code @FormUrlEncoded} requests.
     * Populated by calls to {@link #addFormField(String, String)}.
     */
    private final List<String[]> formFields = new ArrayList<>();

    /**
     * Set to {@code true} when the method carries {@code @FormUrlEncoded}.
     * Controls body serialisation in {@link #resolveBodyPublisher()}.
     */
    private boolean formUrlEncoded = false;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * @param httpMethod   HTTP verb (GET, POST, …)
     * @param baseUrl      client base URL, e.g. {@code https://api.example.com}
     * @param pathTemplate path from the HTTP verb annotation, e.g. {@code /users/{id}}
     * @param objectMapper shared mapper used to serialise JSON request bodies
     */
    public RequestContext(String httpMethod,
                          String baseUrl,
                          String pathTemplate,
                          ObjectMapper objectMapper) {
        this.httpMethod   = httpMethod;
        // Concatenate eagerly; may be fully replaced by overrideFullUrl()
        this.resolvedUrl  = baseUrl + pathTemplate;
        this.objectMapper = objectMapper;
    }

    // ── Mutating methods called by ParameterHandlers ──────────────────────────

    /**
     * Substitutes a {@code {placeholder}} in the path with an already-encoded value.
     * Called by {@link io.declarative.http.handler.PathHandler}.
     *
     * @param placeholder placeholder token including braces, e.g. {@code {id}}
     * @param value       percent-encoded replacement value
     */
    public void replacePath(String placeholder, String value) {
        resolvedUrl = resolvedUrl.replace(placeholder, value);
    }

    /**
     * Replaces the entire URL (base + path) with an absolute URL
     *
     * @param absoluteUrl the complete URL to use, e.g.
     *                    {@code https://cdn.example.com/v2/resource/42}
     */
    public void overrideFullUrl(String absoluteUrl) {
        this.resolvedUrl = absoluteUrl;
    }

    /**
     * Appends a query parameter to the request URI.
     * Called by {@link io.declarative.http.handler.QueryHandler} and
     * {@link io.declarative.http.handler.QueryMapHandler}.
     *
     * @param name  query param name (not encoded; must not be null or blank)
     * @param value already percent-encoded value
     * @throws RestClientException if {@code name} is null or blank
     */
    public void addQueryParam(String name, String value) {
        if (name == null || name.isBlank()) {
            throw new RestClientException(
                    "Query parameter name must not be null or blank");
        }
        queryParams.add(new String[]{name, value});
    }

    /**
     * Adds a request header, overwriting any previous value for the same name.
     * Called by {@link io.declarative.http.handler.HeaderHandler} and
     * {@link io.declarative.http.handler.HeaderMapHandler}.
     *
     * @param name  header name (must not be null or blank)
     * @param value header value
     * @throws RestClientException if {@code name} is null or blank
     */
    public void addHeader(String name, String value) {
        if (name == null || name.isBlank()) {
            throw new RestClientException(
                    "Header name must not be null or blank");
        }
        headers.put(name, value);
    }

    /**
     * Sets the object to be serialised as the JSON request body.
     * Called by {@link io.declarative.http.handler.BodyHandler}.
     *
     * @param body any Jackson-serialisable object; {@code null} produces no body
     */
    public void setBody(Object body) {
        this.body = body;
    }

    /**
     * Accumulates a single form field for {@code @FormUrlEncoded} requests.
     *
     *
     * <p>The value must already be percent-encoded by the caller
     * (see {@link io.declarative.http.handler.FieldHandler}).
     *
     * @param name         the form field name, e.g. {@code username}
     * @param encodedValue the percent-encoded field value
     */
    public void addFormField(String name, String encodedValue) {
        if (name == null || name.isBlank()) {
            throw new RestClientException(
                    "Form field name must not be null or blank");
        }
        formFields.add(new String[]{name, encodedValue});
    }

    /**
     * Switches the request body serialisation strategy to
     * {@code application/x-www-form-urlencoded}.
     *
     * @param formUrlEncoded {@code true} to activate form-encoding mode
     */
    public void setFormUrlEncoded(boolean formUrlEncoded) {
        this.formUrlEncoded = formUrlEncoded;
    }

    // ── Terminal method ───────────────────────────────────────────────────────

    /**
     * Builds the final, immutable {@link HttpRequest} from all accumulated state.
     *
     * <p>Steps:
     * <ol>
     *   <li>Validates that no {@code {placeholder}} tokens remain unresolved.</li>
     *   <li>Appends query parameters to the URI.</li>
     *   <li>Adds a default {@code Accept: application/json} header.</li>
     *   <li>Overlays any user-supplied headers.</li>
     *   <li>Selects the appropriate body publisher (form, JSON, or empty).</li>
     *   <li>Sets the correct {@code Content-Type} header.</li>
     * </ol>
     *
     * @return an immutable, ready-to-send {@link HttpRequest}
     * @throws RestClientException if the URL contains unresolved path variables
     *                             or the body cannot be serialised
     */
    public HttpRequest buildRequest() {
        URI uri = buildUri();

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri);

        // Default Accept — individual @Header params can override this
        builder.header("Accept", "application/json");

        // User-supplied headers (applied after default so they can override)
        headers.forEach(builder::header);

        // Body publisher
        HttpRequest.BodyPublisher publisher = resolveBodyPublisher();
        builder.method(httpMethod, publisher);

        // Content-Type
        if (formUrlEncoded) {
            builder.header("Content-Type", "application/x-www-form-urlencoded");
        } else if (body != null) {
            builder.header("Content-Type", "application/json; charset=UTF-8");
        }

        return builder.build();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Constructs the request {@link URI} from the resolved URL and any
     * accumulated query parameters.
     *
     * @throws RestClientException if the URL still contains {@code {…}} tokens,
     *                             indicating that a {@code @Path} argument was not supplied
     */
    private URI buildUri() {
        if (resolvedUrl.contains("{") && resolvedUrl.contains("}")) {
            throw new RestClientException(
                    "Unresolved path variable in URL: '" + resolvedUrl + "'. " +
                            "Ensure all @Path parameters are provided and non-null.");
        }

        if (queryParams.isEmpty()) {
            return URI.create(resolvedUrl);
        }

        StringJoiner joiner = new StringJoiner("&");
        for (String[] kv : queryParams) {
            joiner.add(kv[0] + "=" + kv[1]);
        }
        return URI.create(resolvedUrl + "?" + joiner);
    }

    /**
     * Selects the {@link HttpRequest.BodyPublisher} based on the current mode.
     *
     * <ul>
     *   <li><b>Form-encoded:</b> joins all accumulated {@link #formFields} with
     *       {@code &} and publishes as UTF-8 bytes.</li>
     *   <li><b>JSON:</b> serialises {@link #body} via Jackson.</li>
     *   <li><b>Empty:</b> used for GET / DELETE methods with no body.</li>
     * </ul>
     */
    private HttpRequest.BodyPublisher resolveBodyPublisher() {
        // ── Form-encoded body ─────────────────────────────────────────────────
        if (formUrlEncoded) {
            if (formFields.isEmpty()) {
                return HttpRequest.BodyPublishers.noBody();
            }
            StringJoiner joiner = new StringJoiner("&");
            for (String[] kv : formFields) {
                joiner.add(kv[0] + "=" + kv[1]);
            }
            return HttpRequest.BodyPublishers.ofString(
                    joiner.toString(), StandardCharsets.UTF_8);
        }

        if (body instanceof String s) {
            return HttpRequest.BodyPublishers.ofString(s);
        }

        // ── JSON body ─────────────────────────────────────────────────────────
        if (body != null) {
            try {
                byte[] json = objectMapper.writeValueAsBytes(body);
                return HttpRequest.BodyPublishers.ofByteArray(json);
            } catch (Exception e) {
                throw new RestClientException(
                        "Failed to serialise request body to JSON: " + e.getMessage(), e);
            }
        }

        // ── No body (GET, DELETE, HEAD, …) ────────────────────────────────────
        return HttpRequest.BodyPublishers.noBody();
    }
}
