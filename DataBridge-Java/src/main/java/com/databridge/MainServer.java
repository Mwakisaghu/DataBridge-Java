package com.databridge;

import io.undertow.Undertow;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.databridge.controller.MigrationController;

public class MainServer {
    private static final Logger logger = LoggerFactory.getLogger(MainServer.class);

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
                        } else if (path.equals("/migrate/all")) {
                            // Handle migration of all tables
                            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");

                            exchange.dispatch(() -> {
                                try {
                                    migrationController.migrateAllTables();
                                    sendResponse(exchange, 200, "{\"status\":\"success\",\"message\":\"All tables migrated successfully.\"}");
                                } catch (Exception e) {
                                    logger.error("Migration error: {}", e.getMessage(), e);
                                    sendResponse(exchange, 500, "{\"status\":\"error\",\"message\":\"Migration failed: " + e.getMessage() + "\"}");
                                }
                            });

                        } else if (pathParts.length == 3 && "migrate".equals(pathParts[1])) {
                            String tableName = pathParts[2];
                            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");

                            exchange.dispatch(() -> {
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

    /**
     * Sends an HTTP response with proper error handling.
     */
    private static void sendResponse(io.undertow.server.HttpServerExchange exchange, int statusCode, String message) {
        if (exchange.isResponseStarted()) {
            logger.warn("Attempted to send response, but it was already started.");
            return;
        }

        exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Origin"), "*");
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.setStatusCode(statusCode);
        exchange.getResponseSender().send(message);
    }

    /**
     * Handles CORS (Cross-Origin Resource Sharing) preflight requests.
     */
    private static void handleCors(io.undertow.server.HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Origin"), "*");
        exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Methods"), "GET, POST, OPTIONS");
        exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Headers"), "Content-Type");
        exchange.setStatusCode(204);
        exchange.endExchange();
    }
}
