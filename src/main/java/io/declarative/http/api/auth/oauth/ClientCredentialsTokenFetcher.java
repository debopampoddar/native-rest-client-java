package io.declarative.http.api.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * JDK-only OAuth 2.0 client-credentials {@link TokenFetcher}.
 *
 * <p>This helper posts {@code application/x-www-form-urlencoded} data to a token
 * endpoint, authenticates the client with HTTP Basic authentication, and converts
 * the {@code access_token}/{@code expires_in} JSON response into an
 * {@link AccessToken}. It never logs client credentials, access tokens, or token
 * endpoint response bodies.
 *
 * <p>The instance is thread-safe after construction and is normally supplied to
 * {@link RefreshingTokenManager}. It deliberately supports only the client-
 * credentials grant; refresh-token and interactive grants remain custom
 * {@link TokenFetcher} implementations.
 */
public final class ClientCredentialsTokenFetcher implements TokenFetcher {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI tokenEndpoint;
    private final String clientId;
    private final String clientSecret;
    private final List<String> scopes;
    private final String audience;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final Clock clock;

    /**
     * Creates immutable token-fetcher state.
     *
     * @param tokenEndpoint OAuth token URI
     * @param clientId client identifier
     * @param clientSecret client secret
     * @param scopes requested OAuth scopes
     * @param audience optional resource-server audience
     * @param httpClient JDK client used only for token acquisition
     * @param requestTimeout token endpoint request timeout
     * @param clock clock used to derive token expiry
     */
    private ClientCredentialsTokenFetcher(URI tokenEndpoint, String clientId,
                                          String clientSecret, List<String> scopes,
                                          String audience, HttpClient httpClient,
                                          Duration requestTimeout, Clock clock) {
        this.tokenEndpoint = tokenEndpoint;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scopes = List.copyOf(scopes);
        this.audience = audience;
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
        this.clock = clock;
    }

    /**
     * Starts construction of a client-credentials token fetcher.
     *
     * @param tokenEndpoint token endpoint using {@code http} or {@code https}
     * @param clientId OAuth client identifier
     * @param clientSecret OAuth client secret
     * @return a builder for optional scopes and transport settings
     */
    public static Builder builder(URI tokenEndpoint, String clientId, String clientSecret) {
        return new Builder(tokenEndpoint, clientId, clientSecret);
    }

    /**
     * Fetches and validates one access token from the authorization server.
     *
     * @return a non-blank, non-expired access token
     * @throws IllegalStateException if the endpoint rejects the request or returns
     *                               malformed token JSON
     */
    @Override
    public AccessToken fetchNewToken() {
        HttpRequest request = HttpRequest.newBuilder(tokenEndpoint)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", basicAuthorization())
                .POST(HttpRequest.BodyPublishers.ofString(formBody(), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("OAuth token endpoint returned HTTP "
                        + response.statusCode());
            }
            return parseToken(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OAuth token request was interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("OAuth token request failed", e);
        }
    }

    /**
     * Builds the OAuth form payload without including the client secret.
     *
     * @return URL-encoded client-credentials request body
     */
    private String formBody() {
        StringJoiner form = new StringJoiner("&");
        form.add(parameter("grant_type", "client_credentials"));
        if (!scopes.isEmpty()) {
            form.add(parameter("scope", String.join(" ", scopes)));
        }
        if (audience != null) {
            form.add(parameter("audience", audience));
        }
        return form.toString();
    }

    /**
     * Produces the HTTP Basic client authentication value without retaining an
     * additional String representation of the secret beyond request construction.
     *
     * @return Authorization header value
     */
    private String basicAuthorization() {
        String credentials = clientId + ":" + clientSecret;
        return "Basic " + Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Parses the minimum safe OAuth token response contract.
     *
     * @param responseBody successful token endpoint response
     * @return validated access token with absolute expiry
     */
    private AccessToken parseToken(String responseBody) {
        try {
            JsonNode response = JSON.readTree(responseBody);
            String value = textValue(response, "access_token");
            long expiresIn = positiveLong(response, "expires_in");
            return new AccessToken(value, Instant.now(clock).plusSeconds(expiresIn));
        } catch (IOException e) {
            throw new IllegalStateException("OAuth token endpoint returned invalid JSON", e);
        }
    }

    /**
     * Obtains a required non-blank string field from a parsed token response.
     *
     * @param response parsed JSON object
     * @param field required JSON field name
     * @return non-blank value
     */
    private static String textValue(JsonNode response, String field) {
        JsonNode value = response.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("OAuth token response is missing " + field);
        }
        return value.asText();
    }

    /**
     * Obtains a required positive numeric lifetime from a parsed token response.
     *
     * @param response parsed JSON object
     * @param field required JSON field name
     * @return positive duration in seconds
     */
    private static long positiveLong(JsonNode response, String field) {
        JsonNode value = response.path(field);
        if (!value.canConvertToLong() || value.asLong() <= 0) {
            throw new IllegalStateException("OAuth token response is missing positive " + field);
        }
        return value.asLong();
    }

    /**
     * Encodes one form parameter using the HTML form encoding required by OAuth
     * token requests.
     *
     * @param name parameter name
     * @param value parameter value
     * @return encoded {@code name=value} pair
     */
    private static String parameter(String name, String value) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Fluent builder for {@link ClientCredentialsTokenFetcher}.
     */
    public static final class Builder {

        private final URI tokenEndpoint;
        private final String clientId;
        private final String clientSecret;
        private final List<String> scopes = new ArrayList<>();
        private String audience;
        private HttpClient httpClient = HttpClient.newBuilder().build();
        private Duration requestTimeout = Duration.ofSeconds(10);
        private Clock clock = Clock.systemUTC();

        /**
         * Creates a builder after validating required OAuth configuration.
         *
         * @param tokenEndpoint token endpoint URI
         * @param clientId client identifier
         * @param clientSecret client secret
         */
        private Builder(URI tokenEndpoint, String clientId, String clientSecret) {
            this.tokenEndpoint = validateEndpoint(tokenEndpoint);
            this.clientId = requireNonBlank(clientId, "clientId");
            this.clientSecret = requireNonBlank(clientSecret, "clientSecret");
        }

        /**
         * Adds one OAuth scope to the request.
         *
         * @param scope non-blank scope name
         * @return this builder
         */
        public Builder scope(String scope) {
            scopes.add(requireNonBlank(scope, "scope"));
            return this;
        }

        /**
         * Replaces the requested scopes.
         *
         * @param scopes scope collection; every value must be non-blank
         * @return this builder
         */
        public Builder scopes(Collection<String> scopes) {
            Objects.requireNonNull(scopes, "scopes");
            this.scopes.clear();
            for (String scope : scopes) {
                scope(scope);
            }
            return this;
        }

        /**
         * Adds an optional audience/resource indicator used by compatible servers.
         *
         * @param audience non-blank audience value
         * @return this builder
         */
        public Builder audience(String audience) {
            this.audience = requireNonBlank(audience, "audience");
            return this;
        }

        /**
         * Uses a caller-configured JDK HTTP client for token acquisition.
         *
         * @param httpClient token transport client
         * @return this builder
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
            return this;
        }

        /**
         * Sets the token endpoint request timeout.
         *
         * @param requestTimeout positive timeout
         * @return this builder
         */
        public Builder requestTimeout(Duration requestTimeout) {
            Objects.requireNonNull(requestTimeout, "requestTimeout");
            if (requestTimeout.isZero() || requestTimeout.isNegative()) {
                throw new IllegalArgumentException("requestTimeout must be positive");
            }
            this.requestTimeout = requestTimeout;
            return this;
        }

        /**
         * Builds the thread-safe token fetcher.
         *
         * @return a ready token fetcher
         */
        public ClientCredentialsTokenFetcher build() {
            return new ClientCredentialsTokenFetcher(tokenEndpoint, clientId, clientSecret,
                    scopes, audience, httpClient, requestTimeout, clock);
        }

        /**
         * Validates a supported absolute OAuth token endpoint URI.
         *
         * @param endpoint token endpoint to validate
         * @return validated endpoint
         */
        private static URI validateEndpoint(URI endpoint) {
            Objects.requireNonNull(endpoint, "tokenEndpoint");
            String scheme = endpoint.getScheme();
            if ((!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)))
                    || endpoint.getHost() == null) {
                throw new IllegalArgumentException("tokenEndpoint must be an absolute HTTP(S) URI");
            }
            return endpoint;
        }

        /**
         * Validates a required secret-adjacent configuration value without echoing it.
         *
         * @param value value to validate
         * @param name property name for diagnostics
         * @return validated value
         */
        private static String requireNonBlank(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }
}
