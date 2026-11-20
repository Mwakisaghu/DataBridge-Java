package com.databridge.app.http;

import com.databridge.core.service.ValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.PathTemplateMatch;

import java.io.IOException;
import java.io.InputStream;
import java.util.Deque;
import java.util.Map;

/** A thin view of an Undertow exchange for endpoint code. */
public final class Request {

    public static final String ACTOR_HEADER = "X-DataBridge-User";

    private final HttpServerExchange exchange;
    private final ObjectMapper json;
    private final Auth auth;

    Request(HttpServerExchange exchange, ObjectMapper json, Auth auth) {
        this.exchange = exchange;
        this.json = json;
        this.auth = auth;
    }

    /** A {name} from the URL template. */
    public String path(String name) {
        PathTemplateMatch m = exchange.getAttachment(PathTemplateMatch.ATTACHMENT_KEY);
        Map<String, String> params = m == null ? Map.of() : m.getParameters();
        String v = params.get(name);
        if (v == null) throw new IllegalStateException("No path parameter " + name);
        return v;
    }

    /** A query-string parameter, or null. */
    public String query(String name) {
        Deque<String> v = exchange.getQueryParameters().get(name);
        return v == null || v.isEmpty() ? null : v.getFirst();
    }

    public String requiredQuery(String name) {
        String v = query(name);
        if (v == null || v.isBlank()) throw new ValidationException("Query parameter '" + name + "' is required");
        return v;
    }

    public String header(String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    /** The signed-in user (in development mode without sign-in: the name in the X-DataBridge-User header). */
    public com.databridge.core.security.User user() {
        com.databridge.core.security.User u = auth.current(exchange);
        if (u == null) throw new com.databridge.core.security.AuthException("Sign in to continue", false);
        return u;
    }

    /** Who is calling, for the audit trail: the signed-in user's user name. */
    public String actor() {
        return user().username();
    }

    /** The session token from the cookie, or null. */
    public String sessionToken() {
        return auth.token(exchange);
    }

    public void startSession(String token, java.time.Instant expires) {
        auth.setSession(exchange, token, expires);
    }

    public void endSession() {
        auth.clearSession(exchange);
    }

    public <T> T body(Class<T> type) throws IOException {
        return json.readValue(exchange.getInputStream(), type);
    }

    /** The JSON body, or null when the request has none. */
    public <T> T optionalBody(Class<T> type) throws IOException {
        JsonNode n = json.readTree(exchange.getInputStream());
        return n == null || n.isMissingNode() || n.isNull() ? null : json.treeToValue(n, type);
    }

    public JsonNode bodyTree() throws IOException {
        JsonNode n = json.readTree(exchange.getInputStream());
        if (n == null || n.isMissingNode()) throw new ValidationException("A JSON body is required");
        return n;
    }

    /** The raw request body, streamed. */
    public InputStream stream() {
        return exchange.getInputStream();
    }
}
