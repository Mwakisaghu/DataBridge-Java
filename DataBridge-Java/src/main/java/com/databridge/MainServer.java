package com.databridge;

import io.undertow.Undertow;
import io.undertow.util.Headers;

public class MainServer {
    public static void main(String[] args) {
        Undertow server = Undertow.builder()
                .addHttpListener(8080, "0.0.0.0")
                .setHandler(exchange -> {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                    exchange.getResponseSender().send("DataBridge-Java is Running 🚀");
                })
                .build();
        server.start();
        System.out.println("Server started on http://localhost:8080");
    }
}
