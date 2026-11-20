package com.databridge.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The real server with sign-in on: sessions, roles per route, the cross-site guard and user admin. */
class AuthApiTest {

    private static final String ADMIN_PW = "first-admin-password-1";
    private static DataBridgeServer server;
    private static String base;
    private static final HttpClient http = HttpClient.newHttpClient();
    private static final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void start() {
        String db = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MSSQLServer;DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1";
        server = DataBridgeServer.start(AppConfig.load(new String[0],
                Map.of("DATABRIDGE_PORT", "0", "DATABRIDGE_DB_URL", db, "DATABRIDGE_ADMIN_PASSWORD", ADMIN_PW)));
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    record Reply(int status, JsonNode body, String text, String cookie) { }

    private static Reply call(String method, String path, String body, String cookie, boolean appHeader) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) b.header("Content-Type", "application/json");
        if (appHeader) b.header("X-DataBridge-Client", "test");
        if (cookie != null) b.header("Cookie", cookie);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        String set = r.headers().firstValue("Set-Cookie").orElse(null);
        return new Reply(r.statusCode(), r.body().isEmpty() ? null : json.readTree(r.body()), r.body(), set);
    }

    private static Reply call(String method, String path, String body, String cookie) throws Exception {
        return call(method, path, body, cookie, true);
    }

    /** Signs in and returns the cookie to send back. */
    private static String signIn(String user, String pw) throws Exception {
        Reply r = call("POST", "/api/auth/login", "{\"username\":\"" + user + "\",\"password\":\"" + pw + "\"}", null);
        assertEquals(200, r.status(), r.text());
        assertTrue(r.cookie().contains("HttpOnly"), r.cookie());
        assertTrue(r.cookie().contains("SameSite=Strict"), r.cookie());
        return r.cookie().split(";")[0];
    }

    private static String adminPassword = ADMIN_PW;

    /** Signs the administrator in, changing the temporary password the first time. */
    private static synchronized String adminCookie() throws Exception {
        String c = signIn("admin", adminPassword);
        if (call("GET", "/api/auth/me", null, c).body().get("user").get("mustChangePassword").asBoolean()) {
            assertEquals(200, call("POST", "/api/auth/password",
                    "{\"currentPassword\":\"" + adminPassword + "\",\"newPassword\":\"" + ADMIN_PW + "-changed\"}", c).status());
            adminPassword = ADMIN_PW + "-changed";
        }
        return c;
    }

    private static String newUser(String admin, String name, String roles) throws Exception {
        Reply r = call("POST", "/api/users", "{\"username\":\"" + name + "\",\"displayName\":\"" + name + "\",\"roles\":" + roles + "}", admin);
        assertEquals(201, r.status(), r.text());
        String temp = r.body().get("temporaryPassword").asText();
        String c = signIn(name, temp);
        assertEquals(200, call("POST", "/api/auth/password",
                "{\"currentPassword\":\"" + temp + "\",\"newPassword\":\"" + name + "-own-password-1\"}", c).status());
        return c;
    }

    @Test
    void anonymousCallersMaySeeHealthButNothingElse() throws Exception {
        assertEquals(200, call("GET", "/health", null, null).status());
        Reply r = call("GET", "/api/overview", null, null);
        assertEquals(401, r.status());
        assertEquals("Sign in to continue", r.body().get("error").asText());
        assertEquals(401, call("POST", "/api/clients", "{\"code\":\"X\",\"name\":\"X\"}", null).status());
    }

    @Test
    void theDevelopmentUserHeaderIsIgnoredWhenSignInIsOn() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/overview")).header("X-DataBridge-User", "admin").GET().build();
        assertEquals(401, http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void temporaryPasswordMustBeChangedBeforeAnythingElse() throws Exception {
        Reply created = call("POST", "/api/users", "{\"username\":\"temp.user\",\"displayName\":\"Temp\",\"roles\":[\"VIEWER\"]}",
                adminCookie());
        String c = signIn("temp.user", created.body().get("temporaryPassword").asText());
        Reply blocked = call("GET", "/api/overview", null, c);
        assertEquals(403, blocked.status());
        assertTrue(blocked.body().get("error").asText().contains("temporary password"));
        assertEquals(200, call("GET", "/api/auth/me", null, c).status(), "me works so the UI can show the change screen");
    }

    @Test
    void rolesDecideWhoMayChangeWhat() throws Exception {
        String admin = adminCookie();
        String viewer = newUser(admin, "vic.viewer", "[\"VIEWER\"]");
        String consultant = newUser(admin, "cora.consultant", "[\"CONSULTANT\"]");

        assertEquals(200, call("GET", "/api/overview", null, viewer).status(), "everyone signed in may read");
        assertEquals(403, call("POST", "/api/clients", "{\"code\":\"V\",\"name\":\"V\"}", viewer).status());
        Reply made = call("POST", "/api/clients", "{\"code\":\"ROLES\",\"name\":\"Roles SACCO\"}", consultant);
        assertEquals(201, made.status(), made.text());
        assertEquals("cora.consultant", made.body().get("createdBy").asText(), "the actor is the signed-in user");

        assertEquals(403, call("PATCH", "/api/setup", "{}", consultant).status(), "setups are for administrators");
        assertEquals(403, call("GET", "/api/users", null, consultant).status());
        assertEquals(403, call("POST", "/api/retention/purge", null, consultant).status());
        assertEquals(200, call("GET", "/api/users", null, admin).status());
    }

    @Test
    void changesWithoutTheAppHeaderAreRefused() throws Exception {
        String admin = adminCookie();
        Reply r = call("POST", "/api/clients", "{\"code\":\"CSRF\",\"name\":\"CSRF\"}", admin, false);
        assertEquals(403, r.status());
        assertTrue(r.body().get("error").asText().contains("X-DataBridge-Client"));
    }

    @Test
    void signOutEndsTheSession() throws Exception {
        String c = adminCookie();
        Reply out = call("POST", "/api/auth/logout", null, c);
        assertEquals(204, out.status());
        assertTrue(out.cookie().contains("Max-Age=0"), out.cookie());
        assertEquals(401, call("GET", "/api/auth/me", null, c).status());
    }

    @Test
    void wrongPasswordsLockTheAccount() throws Exception {
        String admin = adminCookie();
        call("POST", "/api/users", "{\"username\":\"lock.me\",\"displayName\":\"Lock\",\"roles\":[\"VIEWER\"]}", admin);
        for (int i = 0; i < 5; i++) {
            assertEquals(401, call("POST", "/api/auth/login", "{\"username\":\"lock.me\",\"password\":\"not-the-password-1\"}", null).status());
        }
        Reply locked = call("POST", "/api/auth/login", "{\"username\":\"lock.me\",\"password\":\"not-the-password-1\"}", null);
        assertTrue(locked.body().get("error").asText().contains("locked"), locked.text());
    }

    private static Reply upload(String path, byte[] bytes, String cookie) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path)).header("X-DataBridge-Client", "test")
                .header("Cookie", cookie).header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        return new Reply(r.statusCode(), r.body().isEmpty() ? null : json.readTree(r.body()), r.body(), null);
    }

    @Test
    void reconciliationVariancesAreExplainedByAConsultantAndApprovedOnlyByFinance() throws Exception {
        String admin = adminCookie();
        String consultant = newUser(admin, "rec.consultant", "[\"CONSULTANT\"]");
        String reviewer = newUser(admin, "rec.reviewer", "[\"REVIEWER\"]");
        String finance = newUser(admin, "rec.finance", "[\"FINANCE\"]");

        String clientId = call("POST", "/api/clients", "{\"code\":\"RECON\",\"name\":\"Recon SACCO\"}", consultant).body().get("id").asText();
        String projectId = call("POST", "/api/clients/" + clientId + "/projects", "{\"code\":\"BC\",\"name\":\"Go-live\"}", consultant)
                .body().get("id").asText();
        String runId = call("POST", "/api/projects/" + projectId + "/runs", "{\"type\":\"MOCK\",\"cutoffDate\":\"2026-10-31\"}", consultant)
                .body().get("id").asText();
        String sid = call("POST", "/api/runs/" + runId + "/submissions", null, consultant).body().get("id").asText();
        byte[] tx = java.nio.file.Files.readAllBytes(com.databridge.core.service.Samples.dir()
                .resolve("with-errors/Sample Transactional Data Templates.xlsx"));
        assertEquals(201, upload("/api/submissions/" + sid + "/files?kind=TRANSACTIONAL&fileName=Transactional.xlsx", tx, consultant).status());
        Reply done = call("POST", "/api/submissions/" + sid + "/process", null, consultant);
        assertEquals(201, done.status(), done.text());
        String checkId = done.body().get("check").get("id").asText();

        Reply lines = call("GET", "/api/checks/" + checkId + "/reconciliation", null, reviewer);
        assertEquals(200, lines.status(), lines.text());
        assertEquals(10, lines.body().size());
        JsonNode bosa = null;
        for (JsonNode l : lines.body()) if ("102110".equals(l.get("line").get("glAccount").asText())) bosa = l;
        assertEquals("VARIANCE", bosa.get("line").get("status").asText());
        assertEquals(81000.00, bosa.get("line").get("variance").asDouble(), 0.001);
        String fid = bosa.get("finding").get("id").asText();

        assertEquals(200, call("POST", "/api/findings/" + fid + "/waiver",
                "{\"reason\":\"Loan BSL000999 was written off after cutoff; journal to follow\"}", consultant).status());
        Reply byReviewer = call("POST", "/api/findings/" + fid + "/waiver/decision", "{\"approve\":true}", reviewer);
        assertEquals(403, byReviewer.status());
        assertTrue(byReviewer.body().get("error").asText().contains("Finance"));
        assertEquals(200, call("POST", "/api/findings/" + fid + "/waiver/decision", "{\"approve\":true,\"note\":\"Agreed\"}", finance).status());

        HttpResponse<byte[]> pack = http.send(HttpRequest.newBuilder(URI.create(base + "/api/checks/" + checkId + "/reconciliation.xlsx"))
                .header("Cookie", finance).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, pack.statusCode());
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(pack.body()))) {
            var sheet = wb.getSheet("Reconciliation");
            boolean approved = false;
            for (var row : sheet) {
                if (row.getCell(2) != null && "102110".equals(row.getCell(2).getStringCellValue())) {
                    approved = "Approved".equals(row.getCell(12).getStringCellValue())
                            && "rec.finance".equals(row.getCell(13).getStringCellValue());
                }
            }
            assertTrue(approved, "the pack shows who approved the explanation");
        }

        assertEquals(403, call("PUT", "/api/setup/reconciliation/trial-balance", "{\"sheet\":\"TB\"}", finance).status());
        assertEquals(200, call("GET", "/api/setup/reconciliation", null, finance).status());
    }

    @Test
    void signInIsOnByDefaultAndNoneIsRefusedOffThisMachine() {
        assertEquals("local", AppConfig.load(new String[0], Map.of()).authMode());
        assertThrows(IllegalArgumentException.class, () -> AppConfig.load(new String[0],
                Map.of("DATABRIDGE_AUTH", "none", "DATABRIDGE_HOST", "0.0.0.0")));
        assertTrue(!AppConfig.load(new String[0], Map.of("DATABRIDGE_ADMIN_PASSWORD", ADMIN_PW)).toString().contains(ADMIN_PW));
    }
}
