package com.databridge.app.http;

import com.databridge.core.service.NotFoundException;
import com.databridge.core.service.ValidationException;
import com.databridge.core.setup.SetupException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.util.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Turns an {@link Endpoint} into an Undertow handler: runs it on a worker thread (JDBC
 * and file work block), writes the result as JSON, and maps failures to HTTP. Anything
 * unexpected is a 500 with status ERROR: the API fails closed, never answering an
 * unexpected failure with a success code.
 */
public final class Endpoints {

    private static final Logger LOG = LoggerFactory.getLogger(Endpoints.class);

    private final ObjectMapper json;

    public Endpoints(ObjectMapper json) {
        this.json = json;
    }

    public HttpHandler handler(Endpoint endpoint) {
        return new BlockingHandler(exchange -> {
            Response r;
            try {
                r = endpoint.handle(new Request(exchange, json));
            } catch (NotFoundException e) {
                r = Response.of(404, ApiError.of(e.getMessage()));
            } catch (ValidationException | SetupException e) {
                r = Response.of(400, ApiError.of(e.getMessage()));
            } catch (JsonProcessingException e) {
                r = Response.of(400, ApiError.of("Request body is not valid JSON for this endpoint, or names an unknown "
                        + "setting: " + e.getOriginalMessage()));
            } catch (Exception e) {
                LOG.error("Unexpected failure on {} {}", exchange.getRequestMethod(), exchange.getRequestPath(), e);
                r = Response.of(500, ApiError.of("Unexpected failure: " + e.getClass().getSimpleName()));
            }
            write(exchange, r);
        });
    }

    /** A plain handler for 404 / 405, used as the router's fallback. */
    public HttpHandler status(int code, String message) {
        return exchange -> write(exchange, Response.of(code, ApiError.of(message)));
    }

    void write(HttpServerExchange exchange, Response r) throws JsonProcessingException {
        exchange.setStatusCode(r.status());
        exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-store");
        exchange.getResponseHeaders().put(Headers.X_CONTENT_TYPE_OPTIONS, "nosniff");
        if (r.body() == null) {
            exchange.endExchange();
            return;
        }
        String text = r.rawJson() ? (String) r.body() : json.writeValueAsString(r.body());
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getResponseSender().send(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
    }
}
