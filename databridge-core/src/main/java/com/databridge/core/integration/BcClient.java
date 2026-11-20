package com.databridge.core.integration;

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
import java.util.List;
import java.util.function.Function;

/**
 * A small Business Central API client: OAuth2 client credentials (Microsoft Entra ID) and the
 * read-only calls DataBridge needs, listing companies and counting records. It never writes to
 * Business Central and never logs the token or the secret.
 */
public final class BcClient {

    /** A company as Business Central lists it. */
    public record Company(String id, String name, String displayName) { }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final IntegrationSetup setup;
    private final Function<String, String> env;
    private final HttpClient http;
    private final Clock clock;
    private String token;
    private Instant tokenExpires = Instant.EPOCH;

    public BcClient(IntegrationSetup setup, Function<String, String> env, Clock clock) {
        this.setup = setup;
        this.env = env;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.max(1, setup.getTimeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** Companies of the environment: the connection test. */
    public List<Company> companies() {
        JsonNode n = get(setup.resolve(setup.getApiBaseUrl()) + "/api/v2.0/companies");
        List<Company> out = new ArrayList<>();
        for (JsonNode c : n.path("value")) out.add(new Company(c.path("id").asText(), c.path("name").asText(), c.path("displayName").asText()));
        return out;
    }

    /** How many records an entity set holds in the configured company. */
    public long count(EntityMap e) {
        String path = trim(e.getApiPath()) + "/companies(" + setup.getCompanyId() + ")/" + e.getEntitySet();
        JsonNode n = get(setup.resolve(setup.getApiBaseUrl()) + "/" + path + "?$count=true&$top=0");
        JsonNode count = n.get("@odata.count");
        if (count == null || !count.canConvertToLong()) {
            throw new IntegrationException("Business Central did not return a count for " + e.getEntitySet());
        }
        return count.asLong();
    }

    private JsonNode get(String url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(timeout())
                .header("Authorization", "Bearer " + token()).header("Accept", "application/json").GET().build();
        HttpResponse<String> r = send(req, "Business Central");
        if (r.statusCode() == 401 || r.statusCode() == 403) {
            token = null;
            throw new IntegrationException("Business Central refused the app (" + r.statusCode()
                    + "): check that the Entra app is registered in Business Central with API permissions for this company");
        }
        if (r.statusCode() == 404) throw new IntegrationException("Not found in Business Central: check the environment, company id and API path");
        if (r.statusCode() / 100 != 2) throw new IntegrationException("Business Central answered " + r.statusCode() + odataError(r.body()));
        return parse(r.body());
    }

    private synchronized String token() {
        if (token != null && clock.instant().isBefore(tokenExpires)) return token;
        if (!setup.connectable()) {
            throw new IntegrationException("Integration Setup is incomplete: tenant, environment, company and client id are needed");
        }
        String secret = env.apply(setup.getClientSecretEnv());
        if (secret == null || secret.isBlank()) {
            throw new IntegrationException("Set the client secret in the server's environment variable " + setup.getClientSecretEnv()
                    + "; DataBridge does not store secrets");
        }
        String form = "grant_type=client_credentials&client_id=" + enc(setup.getClientId()) + "&client_secret=" + enc(secret)
                + "&scope=" + enc(setup.getScope());
        HttpRequest req = HttpRequest.newBuilder(URI.create(setup.resolve(setup.getTokenUrl()))).timeout(timeout())
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> r = send(req, "Microsoft Entra ID");
        JsonNode n = parse(r.body());
        if (r.statusCode() != 200 || !n.hasNonNull("access_token")) {
            String why = n.path("error_description").asText(n.path("error").asText("answer " + r.statusCode()));
            int cut = why.indexOf('\n');
            throw new IntegrationException("Sign-in to Microsoft Entra ID failed: " + (cut > 0 ? why.substring(0, cut) : why));
        }
        token = n.get("access_token").asText();
        long life = n.path("expires_in").asLong(3600);
        tokenExpires = clock.instant().plusSeconds(Math.max(60, life - 120));
        return token;
    }

    private HttpResponse<String> send(HttpRequest req, String who) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IntegrationException("Cannot reach " + who + " at " + req.uri().getHost() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IntegrationException("Interrupted while calling " + who, e);
        }
    }

    private Duration timeout() {
        return Duration.ofSeconds(Math.max(1, setup.getTimeoutSeconds()));
    }

    private static JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
        } catch (IOException e) {
            throw new IntegrationException("Business Central sent an answer that is not JSON");
        }
    }

    private static String odataError(String body) {
        try {
            String m = JSON.readTree(body).path("error").path("message").asText("");
            return m.isEmpty() ? "" : ": " + m;
        } catch (IOException e) {
            return "";
        }
    }

    private static String trim(String p) {
        String s = p == null ? "api/v2.0" : p.trim();
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
