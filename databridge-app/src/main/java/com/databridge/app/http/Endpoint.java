package com.databridge.app.http;

/** One API operation. */
@FunctionalInterface
public interface Endpoint {
    Response handle(Request request) throws Exception;
}
