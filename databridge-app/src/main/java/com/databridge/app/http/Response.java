package com.databridge.app.http;

/**
 * What an endpoint returns: an HTTP status and a body. The body is JSON-serialised, or already
 * JSON text ({@code rawJson}), or a file ({@code byte[]} with a content type and download name).
 */
public record Response(int status, Object body, boolean rawJson, String contentType, String fileName) {

    public static Response ok(Object body) { return new Response(200, body, false, null, null); }
    public static Response created(Object body) { return new Response(201, body, false, null, null); }
    public static Response noContent() { return new Response(204, null, false, null, null); }
    public static Response notFound() { return new Response(404, ApiError.of("Not found"), false, null, null); }
    public static Response of(int status, Object body) { return new Response(status, body, false, null, null); }
    /** A body that is already JSON text. */
    public static Response rawJson(String json) { return new Response(200, json, true, null, null); }
    /** A file to download. */
    public static Response file(byte[] data, String contentType, String fileName) {
        return new Response(200, data, false, contentType, fileName);
    }

    public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String ZIP = "application/zip";
}
