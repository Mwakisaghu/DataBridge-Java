package com.databridge;

import io.undertow.Undertow;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.BlockingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.databridge.controller.MigrationController;
import com.databridge.controller.ValidationController;
import com.databridge.service.DataUpdater;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class MainServer {
    private static final Logger logger = LoggerFactory.getLogger(MainServer.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) {
        MigrationController migrationController = new MigrationController();

        Undertow server = Undertow.builder()
                .addHttpListener(8081, "0.0.0.0")
                .setHandler(new BlockingHandler(exchange -> {
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
                        } else if (path.equals("/update/multiple") && exchange.getRequestMethod().equalToString("POST")) {
                            handleUpdateMultipleTables(exchange);
                        } else if (path.equals("/validate/members") && exchange.getRequestMethod().equalToString("POST")) {
                            exchange.getRequestReceiver().receiveFullBytes((ex, data) -> {
                                try {
                                    ValidationController validationController = new ValidationController();
                                    String resultJson = validationController.validateExcel(new java.io.ByteArrayInputStream(data));
                                    sendResponse(ex, 200, resultJson);
                                } catch (Exception e) {
                                    logger.error("Validation error: {}", e.getMessage(), e);
                                    sendResponse(ex, 500, "{\"status\":\"error\",\"message\":\"Validation failed: " + e.getMessage() + "\"}");
                                }
                            });
                        } else if (path.equals("/validate/chart-of-accounts") && exchange.getRequestMethod().equalToString("POST")) {
                            exchange.getRequestReceiver().receiveFullBytes((ex, data) -> {
                                try {
                                    ValidationController validationController = new ValidationController();
                                    String resultJson = validationController.validateChartOfAccounts(new java.io.ByteArrayInputStream(data));
                                    sendResponse(ex, 200, resultJson);
                                } catch (Exception e) {
                                    logger.error("Chart of Accounts validation error: {}", e.getMessage(), e);
                                    sendResponse(ex, 500, "{\"status\":\"error\",\"message\":\"Validation failed: " + e.getMessage() + "\"}");
                                }
                            });
                        } else {
                            sendResponse(exchange, 404, "{\"status\":\"error\",\"message\":\"Endpoint not found.\"}");
                        }
                    } catch (Exception e) {
                        logger.error("Unhandled error: {}", e.getMessage(), e);
                        sendResponse(exchange, 500, "{\"status\":\"error\",\"message\":\"Internal Server Error.\"}");
                    }
                }))
                .build();

        server.start();
        logger.info("Server started on http://localhost:8081");
    }

    private static void handleUpdateMultipleTables(HttpServerExchange exchange) {
        exchange.getRequestReceiver().receiveFullBytes((exchange1, message) -> {
            try {
                String requestBody = new String(message, StandardCharsets.UTF_8);
                Map<String, List<String>> requestMap = objectMapper.readValue(requestBody, new TypeReference<Map<String, List<String>>>() {});
                List<String> tables = requestMap.get("tables");

                if (tables == null || tables.isEmpty()) {
                    sendResponse(exchange1, 400, "{\"status\":\"error\",\"message\":\"Missing or empty 'tables' parameter.\"}");
                    return;
                }

                String result = DataUpdater.updateMultipleTables(tables);
                sendResponse(exchange1, 200, "{\"status\":\"success\",\"message\":\"" + result + "\"}");
            } catch (Exception e) {
                logger.error("Update error: {}", e.getMessage(), e);
                sendResponse(exchange1, 500, "{\"status\":\"error\",\"message\":\"Update failed: " + e.getMessage() + "\"}");
            }
        });
    }

    private static void sendResponse(HttpServerExchange exchange, int statusCode, String message) {
        if (exchange.isResponseStarted()) {
            logger.warn("Attempted to send response, but it was already started.");
            return;
        }

        exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Origin"), "*");
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.setStatusCode(statusCode);
        exchange.getResponseSender().send(message);
    }

    private static void handleCors(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Origin"), "*");
        exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Methods"), "GET, POST, OPTIONS");
        exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Headers"), "Content-Type");
        exchange.setStatusCode(204);
        exchange.endExchange();
    }
}