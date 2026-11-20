package com.databridge.app;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The server hosting the built UI next to the API, with security headers on every answer. */
class WebServingTest {

    private static DataBridgeServer server;
    private static String base;
    private static final HttpClient http = HttpClient.newHttpClient();
    private static Path ui;

    @BeforeAll
    static void start() throws Exception {
        ui = Files.createTempDirectory("databridge-ui");
        Files.writeString(ui.resolve("index.html"), "<!doctype html><title>DataBridge</title><div id=root></div>");
        Files.createDirectories(ui.resolve("assets"));
        Files.writeString(ui.resolve("assets/app-abc123.js"), "console.log('ui')");
        String db = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MSSQLServer;DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1";
        server = DataBridgeServer.start(AppConfig.load(new String[0], Map.of("DATABRIDGE_PORT", "0", "DATABRIDGE_DB_URL", db,
                "DATABRIDGE_UI_DIR", ui.toString(), "DATABRIDGE_ADMIN_PASSWORD", "first-admin-password-1")));
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theAppIsServedAtTheRootAndForEveryAppRoute() throws Exception {
        HttpResponse<String> root = get("/");
        assertEquals(200, root.statusCode());
        assertTrue(root.body().contains("<div id=root>"));
        assertEquals("no-cache", root.headers().firstValue("Cache-Control").orElse(""));
        HttpResponse<String> deep = get("/checks/123");
        assertEquals(200, deep.statusCode(), "a reload inside the app gets the app");
        assertTrue(deep.body().contains("<div id=root>"));
    }

    @Test
    void hashedAssetsAreCachedForGood() throws Exception {
        HttpResponse<String> js = get("/assets/app-abc123.js");
        assertEquals(200, js.statusCode());
        assertTrue(js.headers().firstValue("Cache-Control").orElse("").contains("immutable"));
    }

    @Test
    void theApiStillAnswersAndUnknownApiPathsAreNotTheApp() throws Exception {
        assertEquals(200, get("/health").statusCode());
        assertEquals(401, get("/api/overview").statusCode());
        HttpResponse<String> unknown = get("/api/nothing-here");
        assertTrue(unknown.statusCode() == 401 || unknown.statusCode() == 404, "never the index page");
        assertTrue(!unknown.body().contains("<div id=root>"));
    }

    @Test
    void securityHeadersOnEveryAnswer() throws Exception {
        for (String p : new String[] {"/", "/health", "/api/overview"}) {
            HttpResponse<String> r = get(p);
            assertTrue(r.headers().firstValue("Content-Security-Policy").orElse("").contains("frame-ancestors 'none'"), p);
            assertEquals("DENY", r.headers().firstValue("X-Frame-Options").orElse(""), p);
            assertEquals("no-referrer", r.headers().firstValue("Referrer-Policy").orElse(""), p);
        }
    }

    @Test
    void pathsCannotEscapeTheUiFolder() throws Exception {
        Files.writeString(ui.getParent().resolve("secret-" + UUID.randomUUID() + ".txt"), "secret");
        HttpResponse<String> r = get("/../secret.txt");
        assertTrue(!r.body().contains("secret"));
        HttpResponse<String> enc = get("/%2e%2e/%2e%2e/etc/passwd");
        assertTrue(!enc.body().contains("root:"));
    }

    @Test
    void aUiFolderWithoutTheAppIsRefusedAtStart() throws Exception {
        Path empty = Files.createTempDirectory("databridge-empty-ui");
        String db = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MSSQLServer;DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1";
        RuntimeException e = assertThrows(RuntimeException.class, () -> DataBridgeServer.start(AppConfig.load(new String[0],
                Map.of("DATABRIDGE_PORT", "0", "DATABRIDGE_DB_URL", db, "DATABRIDGE_UI_DIR", empty.toString(), "DATABRIDGE_AUTH", "none"))));
        assertTrue(String.valueOf(e.getMessage()).contains("index.html") || String.valueOf(e.getCause()).contains("index.html"), e.toString());
    }
}
