package com.databridge.app.http;

/** What an endpoint returns: an HTTP status and a body (JSON-serialised; null for none). */
public record Response(int status, Object body, boolean rawJson) {

    public static Response ok(Object body) { return new Response(200, body, false); }
    public static Response created(Object body) { return new Response(201, body, false); }
    public static Response noContent() { return new Response(204, null, false); }
    public static Response notFound() { return new Response(404, ApiError.of("Not found"), false); }
    public static Response of(int status, Object body) { return new Response(status, body, false); }
    /** A body that is already JSON text. */
    public static Response rawJson(String json) { return new Response(200, json, true); }
}
