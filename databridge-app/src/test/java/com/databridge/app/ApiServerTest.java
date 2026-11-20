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
                Map.of("DATABRIDGE_PORT", "0", "DATABRIDGE_DB_URL", db, "DATABRIDGE_AUTH", "none")));
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
    void overviewNestsProjectsRunsAndDatasetCounts() throws Exception {
        String runId = openRun("OVERVIEW");
        upload(runId, "a.xlsx", membersWorkbook());
        upload(runId, "bad.xlsx", new byte[] {1, 2, 3});
        Reply r = call("GET", "/api/overview", null);
        assertEquals(200, r.status(), r.text());
        JsonNode client = null;
        for (JsonNode c : r.body()) if (c.get("client").get("code").asText().equals("OVERVIEW")) client = c;
        JsonNode run = client.get("projects").get(0).get("runs").get(0);
        assertEquals(runId, run.get("run").get("id").asText());
        assertEquals(2, run.get("datasets").asInt());
        assertEquals(1, run.get("failedDatasets").asInt());
    }

    @Test
    void startupProblemsAreExplainedInOneLine() {
        AppConfig c = AppConfig.load(new String[0], Map.of("DATABRIDGE_DB_URL", "jdbc:h2:mem:x"));
        String msg = DataBridgeServer.startupProblem(new RuntimeException(new java.net.BindException("Address already in use")), c);
        assertTrue(msg.startsWith("port 8085 on 127.0.0.1 is already in use"), msg);
        assertTrue(msg.contains("DATABRIDGE_PORT"));
    }

    @Test
    void defaultDatabaseKeepsDataBetweenRestarts() {
        AppConfig c = AppConfig.load(new String[0], Map.of());
        assertTrue(c.dbUrl().startsWith("jdbc:h2:file:"), c.dbUrl());
    }

    @Test
    void submissionValidatesWorkbooksTogetherAndWaiversAreMakerChecker() throws Exception {
        String runId = openRun("SUBMIT");
        Reply sub = call("POST", "/api/runs/" + runId + "/submissions", null);
        assertEquals(201, sub.status(), sub.text());
        String sid = sub.body().get("id").asText();
        Reply file = send("POST", "/api/submissions/" + sid + "/files?kind=STATIC&fileName=Static.xlsx", "application/octet-stream",
                HttpRequest.BodyPublishers.ofByteArray(com.databridge.core.service.FindingServiceTestData.members()));
        assertEquals(201, file.status(), file.text());
        Reply done = call("POST", "/api/submissions/" + sid + "/process", null);
        assertEquals(201, done.status(), done.text());
        JsonNode check = done.body().get("check");
        assertEquals("FAILED", check.get("status").asText());
        assertEquals(2, check.get("critical").asInt());
        assertEquals(404, call("GET", "/api/submissions/" + sid, null).status(), "processed submissions are gone");

        String checkId = check.get("id").asText();
        Reply page = call("GET", "/api/checks/" + checkId + "/findings?severity=critical&limit=1", null);
        assertEquals(2, page.body().get("total").asInt());
        String fid = page.body().get("items").get(0).get("id").asText();
        assertFalse(page.text().contains("A12345678\""), "values masked");

        assertEquals(200, call("POST", "/api/findings/" + fid + "/waiver", "{\"reason\":\"Confirmed with the client\"}").status());
        Reply self = call("POST", "/api/findings/" + fid + "/waiver/decision", "{\"approve\":true}");
        assertEquals(400, self.status(), "same user cannot approve");
        HttpRequest other = HttpRequest.newBuilder(URI.create(base + "/api/findings/" + fid + "/waiver/decision"))
                .header("X-DataBridge-User", "reviewer").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"approve\":true,\"note\":\"ok\"}")).build();
        assertEquals(200, http.send(other, HttpResponse.BodyHandlers.ofString()).statusCode());

        HttpResponse<byte[]> xlsx = http.send(HttpRequest.newBuilder(URI.create(base + "/api/checks/" + checkId + "/register.xlsx")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, xlsx.statusCode());
        assertTrue(xlsx.headers().firstValue("Content-Disposition").orElse("").contains("exception-register-check-1.xlsx"));
        assertEquals('P', (char) xlsx.body()[0], "a zip-based .xlsx");

        Reply checks = call("GET", "/api/runs/" + runId + "/checks", null);
        assertEquals(1, checks.body().size());
        assertTrue(checks.body().get(0).get("counts").toString().contains("WAIVED"));
    }

    @Test
    void ruleSetupCanBeReadAndChanged() throws Exception {
        Reply rules = call("GET", "/api/setup/rules", null);
        assertTrue(rules.body().size() >= 40);
        Reply put = call("PUT", "/api/setup/rules/members.email",
                "{\"type\":\"FORMAT\",\"sheet\":\"Members\",\"field\":\"E-Mail\",\"severity\":\"INFO\",\"pattern\":\"EMAIL\"}");
        assertEquals(200, put.status(), put.text());
        assertEquals(400, call("PUT", "/api/setup/rules/x.y", "{\"type\":\"FORMAT\",\"sheet\":\"Members\",\"field\":\"Name\"}").status());
        assertEquals(204, call("DELETE", "/api/setup/rules/members.email", null).status());
    }

    @Test
    void reconciliationSetupCanBeReadAndChanged() throws Exception {
        Reply setup = call("GET", "/api/setup/reconciliation", null);
        assertEquals(200, setup.status(), setup.text());
        assertEquals(9, setup.body().get("controls").size());
        Reply put = call("PUT", "/api/setup/reconciliation/controls/bank-balances",
                "{\"glAccount\":\"104101\",\"name\":\"Nyota Bank\",\"normal\":\"DEBIT\","
                + "\"sources\":[{\"sheet\":\"BANK BALANCES\",\"amountField\":\"Balance as at cutoff date\",\"whereField\":\"No\",\"whereEquals\":\"BNK001\"}]}");
        assertEquals(200, put.status(), put.text());
        assertEquals(10, call("GET", "/api/setup/reconciliation", null).body().get("controls").size());
        assertEquals(400, call("PUT", "/api/setup/reconciliation/controls/bad", "{\"glAccount\":\"1\",\"normal\":\"DEBIT\"}").status(),
                "a control needs a subledger");
        assertEquals(204, call("DELETE", "/api/setup/reconciliation/controls/bank-balances", null).status());
        assertEquals(200, call("PUT", "/api/setup/reconciliation/trial-balance",
                "{\"sheet\":\"TRIAL BALANCE\",\"accountField\":\"No.\",\"debitField\":\"Debit\",\"creditField\":\"Credit\",\"tolerance\":0.01}").status());
        assertTrue(call("GET", "/api/setup/reconciliation", null).body().get("trialBalanceStored").asBoolean());
        assertEquals(204, call("DELETE", "/api/setup/reconciliation/trial-balance", null).status());
    }

    @Test
    void cleanAndDownloadReturnsTheCleanedWorkbookAndKeepsOnlyTheChangeLog() throws Exception {
        String runId = openRun("CLEANAPI");
        byte[] stat = java.nio.file.Files.readAllBytes(com.databridge.core.service.Samples.dir()
                .resolve("with-errors/Sample Static Data Templates.xlsx"));
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/runs/" + runId + "/clean?kind=STATIC&fileName="
                        + URLEncoder.encode("Sample Static Data Templates.xlsx", StandardCharsets.UTF_8)))
                .header("X-DataBridge-User", "harry").header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(stat)).build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, res.statusCode(), () -> new String(res.body(), StandardCharsets.UTF_8));
        assertTrue(res.headers().firstValue("Content-Disposition").orElse("").contains("Sample Static Data Templates (cleaned).xlsx"));
        String jobId = res.headers().firstValue("X-DataBridge-Cleaning-Job").orElseThrow();
        try (var wb = new XSSFWorkbook(new java.io.ByteArrayInputStream(res.body()))) {
            assertEquals("+254712345678", wb.getSheet("Members").getRow(42).getCell(8).getStringCellValue());
        }

        Reply job = call("GET", "/api/cleaning-jobs/" + jobId, null);
        assertEquals(200, job.status(), job.text());
        assertTrue(job.body().get("job").get("cells").asInt() >= 4);
        assertFalse(job.text().contains("712345678"), "the change log holds no values");
        assertEquals(1, call("GET", "/api/runs/" + runId + "/cleaning-jobs", null).body().size());

        assertTrue(call("GET", "/api/setup/cleaning", null).body().size() >= 10);
        assertEquals(200, call("PUT", "/api/setup/cleaning/members.names",
                "{\"sheet\":\"Members\",\"field\":\"Name\",\"action\":\"UPPER\"}").status());
        assertEquals(400, call("PUT", "/api/setup/cleaning/bad.pad", "{\"sheet\":\"Members\",\"field\":\"No.\",\"action\":\"PAD_LEFT\"}").status());
        assertEquals(204, call("DELETE", "/api/setup/cleaning/members.names", null).status());

        Reply bad = send("POST", "/api/runs/" + runId + "/clean?kind=STATIC&fileName=x.xlsx", "application/octet-stream",
                HttpRequest.BodyPublishers.ofByteArray("not a workbook".getBytes(StandardCharsets.UTF_8)));
        assertEquals(400, bad.status());
        assertTrue(bad.body().get("error").asText().contains("could not be cleaned"));
    }

    @Test
    void integrationSetupPackagesAndImportTracking() throws Exception {
        Reply setup = call("GET", "/api/setup/integration", null);
        assertEquals(200, setup.status(), setup.text());
        assertTrue(setup.body().get("setup").get("requireCleanCheck").asBoolean());
        assertFalse(setup.text().contains("secret\":\""), "no secret value in the answer");

        Reply test = call("POST", "/api/setup/integration/test", null);
        assertEquals(200, test.status());
        assertFalse(test.body().get("ok").asBoolean());
        assertTrue(test.body().get("message").asText().contains("incomplete"));

        String runId = openRun("PKGAPI");
        byte[] stat = java.nio.file.Files.readAllBytes(com.databridge.core.service.Samples.dir().resolve("clean/Sample Static Data Templates.xlsx"));
        String sid = call("POST", "/api/runs/" + runId + "/submissions", null).body().get("id").asText();
        send("POST", "/api/submissions/" + sid + "/files?kind=STATIC&fileName=Static.xlsx", "application/octet-stream",
                HttpRequest.BodyPublishers.ofByteArray(stat));
        assertEquals(201, call("POST", "/api/submissions/" + sid + "/process", null).status());

        HttpResponse<byte[]> pkg = http.send(HttpRequest.newBuilder(URI.create(base + "/api/runs/" + runId + "/package?kind=STATIC&fileName=Static.xlsx"))
                .header("X-DataBridge-User", "harry").header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(stat)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, pkg.statusCode(), () -> new String(pkg.body(), StandardCharsets.UTF_8));
        assertTrue(pkg.headers().firstValue("X-DataBridge-Package-Job").isPresent());
        try (var wb = new XSSFWorkbook(new java.io.ByteArrayInputStream(pkg.body()))) {
            assertEquals("Import order", wb.getSheetName(0));
        }
        assertEquals(1, call("GET", "/api/runs/" + runId + "/packages", null).body().size());
        Reply imports = call("GET", "/api/runs/" + runId + "/imports", null);
        assertTrue(imports.body().size() > 10);
        Reply rec = call("PUT", "/api/runs/" + runId + "/imports/" + URLEncoder.encode("Members", StandardCharsets.UTF_8),
                "{\"importedRows\":150}");
        assertEquals(200, rec.status(), rec.text());
        assertEquals("IMPORTED", rec.body().get("status").asText());

        Reply verify = call("POST", "/api/runs/" + runId + "/imports/verify", null);
        assertEquals(502, verify.status(), "Business Central not configured: a bad gateway, explained");
        assertTrue(verify.body().get("error").asText().contains("Integration Setup is incomplete"));
    }

    @Test
    void runSummarySignOffAndExports() throws Exception {
        String runId = openRun("REPORTS");
        Reply sum = call("GET", "/api/runs/" + runId + "/summary", null);
        assertEquals(200, sum.status(), sum.text());
        assertFalse(sum.body().get("ready").asBoolean());
        assertEquals(4, sum.body().get("checklist").size());
        assertEquals("REPORTS", sum.body().get("client").get("code").asText());

        Reply notReady = call("POST", "/api/runs/" + runId + "/signoff", null);
        assertEquals(400, notReady.status(), "no body is fine; not being ready is not");
        assertTrue(notReady.body().get("error").asText().startsWith("The run is not ready for sign-off"));
        assertEquals(400, call("POST", "/api/runs/" + runId + "/signoff/decision", "{\"approve\":true}").status());

        HttpResponse<byte[]> pack = http.send(HttpRequest.newBuilder(URI.create(base + "/api/runs/" + runId + "/signoff-pack.xlsx")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, pack.statusCode());
        try (var wb = new XSSFWorkbook(new java.io.ByteArrayInputStream(pack.body()))) {
            assertEquals("Not requested", wb.getSheet("Sign-off").getRow(4).getCell(1).getStringCellValue());
        }
        HttpResponse<byte[]> audit = http.send(HttpRequest.newBuilder(URI.create(base + "/api/audit.xlsx?limit=50")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, audit.statusCode());
        try (var wb = new XSSFWorkbook(new java.io.ByteArrayInputStream(audit.body()))) {
            assertTrue(wb.getSheet("Audit trail").getLastRowNum() >= 1);
        }
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
