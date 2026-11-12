package com.databridge.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.databridge.core.support.TestWorkbooks.caption;
import static com.databridge.core.support.TestWorkbooks.set;
import static com.databridge.core.support.TestWorkbooks.workbook;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Starts the real Undertow server on a free port over a fresh H2 database and calls it over HTTP. */
class ApiServerTest {

    @TempDir static Path tmp;
    private static DataBridgeServer server;
    private static String base;
    private static final HttpClient http = HttpClient.newHttpClient();
    private static final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void start() {
        String db = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MSSQLServer;DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1";
        server = DataBridgeServer.start(AppConfig.load(new String[0],
                Map.of("DATABRIDGE_PORT", "0", "DATABRIDGE_DB_URL", db)));
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    record Reply(int status, JsonNode body, String text) { }

    private static Reply send(String method, String path, String contentType, HttpRequest.BodyPublisher body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("X-DataBridge-User", "harry")
                .method(method, body);
        if (contentType != null) b.header("Content-Type", contentType);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode n = r.body().isEmpty() ? null : json.readTree(r.body());
        return new Reply(r.statusCode(), n, r.body());
    }

    private static Reply call(String method, String path, String jsonBody) throws Exception {
        return send(method, path, jsonBody == null ? null : "application/json",
                jsonBody == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(jsonBody));
    }

    private static byte[] membersWorkbook() throws Exception {
        XSSFWorkbook wb = workbook();
        Sheet s = wb.createSheet("Members");
        set(s, 0, 0, "STATIC DATA");
        set(s, 0, 1, "Members");
        set(s, 0, 2, 52185700);
        caption(s, 2, 0, "No.", "Code50");
        caption(s, 2, 1, "Name", "Text80");
        set(s, 3, 0, "152009");
        set(s, 3, 1, "JOSEPH DOE DOE");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        wb.close();
        return out.toByteArray();
    }

    private static String openRun(String clientCode) throws Exception {
        String clientId = call("POST", "/api/clients", "{\"code\":\"" + clientCode + "\",\"name\":\"" + clientCode + " SACCO\"}")
                .body().get("id").asText();
        String projectId = call("POST", "/api/clients/" + clientId + "/projects", "{\"code\":\"BC\",\"name\":\"Go-live\"}")
                .body().get("id").asText();
        Reply run = call("POST", "/api/projects/" + projectId + "/runs", "{\"type\":\"MOCK\",\"cutoffDate\":\"2027-01-31\"}");
        assertEquals(201, run.status(), run.text());
        assertEquals("2027-01-31", run.body().get("cutoffDate").asText());
        return run.body().get("id").asText();
    }

    private static Reply upload(String runId, String fileName, byte[] bytes) throws Exception {
        return send("POST", "/api/runs/" + runId + "/datasets?kind=static&fileName="
                        + URLEncoder.encode(fileName, StandardCharsets.UTF_8),
                "application/octet-stream", HttpRequest.BodyPublishers.ofByteArray(bytes));
    }

    @Test
    void healthReportsDatabase() throws Exception {
        Reply r = call("GET", "/health", null);
        assertEquals(200, r.status());
        assertEquals("UP", r.body().get("database").asText());
    }

    @Test
    void uploadFlowStoresStructureOnlyAndPurgesTheFile() throws Exception {
        Path ws = tmp.resolve("workspaces");
        String dir = ws.toString().replace("\\", "/");
        Reply patched = call("PATCH", "/api/setup", "{\"workspaceDir\":\"" + dir + "\"}");
        assertEquals(200, patched.status(), patched.text());
        assertEquals("PURGE_ON_COMPLETE", patched.body().get("retention").get("mode").asText(), "unsent values kept");

        String runId = openRun("AZIMA");
        Reply up = upload(runId, "Static Data Templates.xlsx", membersWorkbook());
        assertEquals(201, up.status(), up.text());
        assertEquals("SUCCEEDED", up.body().get("dataset").get("status").asText());
        assertEquals(52185700, up.body().get("sheets").get(0).get("tableId").asInt());
        assertEquals("Static Data Templates.xlsx", up.body().get("dataset").get("fileName").asText());

        String id = up.body().get("dataset").get("id").asText();
        Reply schema = call("GET", "/api/datasets/" + id + "/schema", null);
        assertEquals(200, schema.status());
        assertFalse(schema.text().contains("JOSEPH"), "schema holds structure only");
        try (Stream<Path> left = Files.list(ws)) {
            assertEquals(0, left.count(), "workspace purged after upload");
        }

        Reply audit = call("GET", "/api/audit?entityType=DATASET", null);
        assertEquals("DATASET_UPLOADED", audit.body().get(0).get("action").asText());
        assertEquals("harry", audit.body().get(0).get("actor").asText());
        assertTrue(audit.body().get(0).get("occurredAt").asText().endsWith("Z"), "ISO timestamps");
    }

    @Test
    void corruptWorkbookIsUnprocessableNotSuccess() throws Exception {
        String runId = openRun("DESACCO");
        Reply r = upload(runId, "bad.xlsx", new byte[] {1, 2, 3});
        assertEquals(422, r.status(), r.text());
        assertEquals("ERROR", r.body().get("dataset").get("status").asText());
    }

    @Test
    void uploadNeedsKindAndFileName() throws Exception {
        String runId = openRun("KIND");
        Reply r = send("POST", "/api/runs/" + runId + "/datasets?kind=STATIC", "application/octet-stream",
                HttpRequest.BodyPublishers.ofByteArray(new byte[] {1}));
        assertEquals(400, r.status());
        assertTrue(r.body().get("error").asText().contains("fileName"));
    }

    @Test
    void misspeltSettingIsRejected() throws Exception {
        Reply r = call("PATCH", "/api/setup", "{\"retension\":{\"mode\":\"KEEP_FOR_HOURS\"}}");
        assertEquals(400, r.status(), r.text());
    }

    @Test
    void invalidTemplateSetupIsRejected() throws Exception {
        Reply r = call("PUT", "/api/setup/templates/Loans", "{\"fields\":[{\"caption\":\"Amount\",\"type\":\"Money\"}]}");
        assertEquals(400, r.status());
        assertEquals("ERROR", r.body().get("status").asText());
    }

    @Test
    void templateSetupWithSpacesInNameIsStoredAndExported() throws Exception {
        Reply r = call("PUT", "/api/setup/templates/Loan%20Recovery%20Header", "{\"tableId\":52187020}");
        assertEquals(200, r.status(), r.text());
        assertEquals("Loan Recovery Header", r.body().get("sheetName").asText());
        Reply export = call("GET", "/api/setup/export", null);
        assertTrue(export.text().contains("52187020"));
    }

    @Test
    void unknownThingsAndWrongMethodsAreReported() throws Exception {
        assertEquals(404, call("GET", "/api/runs/nope", null).status());
        assertEquals(404, call("GET", "/api/no-such-endpoint", null).status());
        assertEquals(405, call("DELETE", "/api/clients", null).status());
    }

    @Test
    void purgeAllIsAudited() throws Exception {
        Reply r = call("POST", "/api/retention/purge?scope=all", null);
        assertEquals(200, r.status(), r.text());
        assertEquals(400, call("POST", "/api/retention/purge?scope=everything", null).status());
    }
}
