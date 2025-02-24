package com.databridge;

import io.undertow.Undertow;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.databridge.controller.MigrationController;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainServer {
    private static final Logger logger = LoggerFactory.getLogger(MainServer.class);
    private static final ExecutorService executorService = Executors.newFixedThreadPool(10); // Thread pool for async execution

    public static void main(String[] args) {
        MigrationController migrationController = new MigrationController();

        Undertow server = Undertow.builder()
                .addHttpListener(8081, "0.0.0.0")
                .setHandler(exchange -> {
                    try {
                        // Handle CORS Preflight Requests
                        if (exchange.getRequestMethod().equalToString("OPTIONS")) {
                            handleCors(exchange);
                            return;
                        }

                        String path = exchange.getRequestPath();
                        String[] pathParts = path.split("/");

                        if (path.equals("/")) {
                            sendResponse(exchange, 200, "DataBridge-Java is Running...");
                        } else if (pathParts.length == 3 && "migrate".equals(pathParts[1])) {
                            String tableName = pathParts[2];
                            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");

                            executorService.submit(() -> { // Run migration in a separate thread
                                try {
                                    String result = migrationController.migrateTable(tableName);
                                    sendResponse(exchange, 200, "{\"status\":\"success\",\"message\":\"" + result + "\"}");
                                } catch (Exception e) {
                                    logger.error("Migration error: {}", e.getMessage(), e);
                                    sendResponse(exchange, 500, "{\"status\":\"error\",\"message\":\"Migration failed: " + e.getMessage() + "\"}");
                                }
                            });

                        } else {
                            sendResponse(exchange, 404, "{\"status\":\"error\",\"message\":\"Endpoint not found.\"}");
                        }
                    } catch (Exception e) {
                        logger.error("Unhandled error: {}", e.getMessage(), e);
                        sendResponse(exchange, 500, "{\"status\":\"error\",\"message\":\"Internal Server Error.\"}");
                    }
                })
                .build();

        server.start();
        logger.info("Server started on http://localhost:8081");
    }

    private static void sendResponse(io.undertow.server.HttpServerExchange exchange, int statusCode, String message) {
        exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Origin"), "*"); // Corrected CORS Header
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.setStatusCode(statusCode);
        exchange.getResponseSender().send(message);
    }

    private static void handleCors(io.undertow.server.HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Origin"), "*");
        exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Methods"), "GET, POST, OPTIONS");
        exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Headers"), "Content-Type");
        exchange.setStatusCode(204);
        exchange.endExchange();
    }
}
