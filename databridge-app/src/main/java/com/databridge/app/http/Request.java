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

    Request(HttpServerExchange exchange, ObjectMapper json) {
        this.exchange = exchange;
        this.json = json;
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

    /**
     * Who is calling. Until sign-in arrives (P0-03) callers name themselves in the
     * X-DataBridge-User header; the server listens on 127.0.0.1 meanwhile.
     */
    public String actor() {
        String h = header(ACTOR_HEADER);
        return h == null || h.isBlank() ? "local" : h.trim();
    }

    public <T> T body(Class<T> type) throws IOException {
        return json.readValue(exchange.getInputStream(), type);
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
