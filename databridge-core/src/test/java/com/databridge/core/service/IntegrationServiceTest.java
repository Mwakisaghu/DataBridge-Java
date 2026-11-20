package com.databridge.core.service;

import com.databridge.core.domain.Client;
import com.databridge.core.domain.ImportRecord;
import com.databridge.core.domain.MigrationRun;
import com.databridge.core.domain.PackageJob;
import com.databridge.core.domain.Project;
import com.databridge.core.domain.RunType;
import com.databridge.core.domain.TemplateKind;
import com.databridge.core.integration.EntityMap;
import com.databridge.core.integration.IntegrationSetup;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationServiceTest {

    @TempDir Path tmp;
    private Fixture fx;
    private MigrationRun run;

    // ------------------------------------------------------------------ fake Business Central

    private HttpServer bc;
    private final AtomicInteger tokens = new AtomicInteger();
    private final List<String> seenAuth = new ArrayList<>();
    private static final String COMPANY = "11111111-2222-3333-4444-555555555555";

    @BeforeEach
    void setUp() throws IOException {
        fx = Fixture.inMemory(tmp);
        Client c = fx.migrations.createClient("PKG", "PKG SACCO", "harry");
        Project p = fx.migrations.createProject(c.id(), "BC", "Go-live", null, "harry");
        run = fx.migrations.openRun(p.id(), RunType.MOCK, LocalDate.of(2026, 10, 31), "harry");

        bc = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bc.createContext("/tenant-a/oauth2/v2.0/token", ex -> {
            String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!URLDecoder.decode(form, StandardCharsets.UTF_8).contains("client_secret=s3cret")) {
                send(ex, 401, "{\"error\":\"invalid_client\",\"error_description\":\"AADSTS7000215: Invalid client secret provided.\\nTrace\"}");
                return;
            }
            tokens.incrementAndGet();
            send(ex, 200, "{\"access_token\":\"tok-1\",\"expires_in\":3599,\"token_type\":\"Bearer\"}");
        });
        bc.createContext("/v2.0/tenant-a/Sandbox/", ex -> {
            seenAuth.add(ex.getRequestHeaders().getFirst("Authorization"));
            String path = ex.getRequestURI().getPath();
            if (path.endsWith("/api/v2.0/companies")) {
                send(ex, 200, "{\"value\":[{\"id\":\"" + COMPANY + "\",\"name\":\"SAMPLE\",\"displayName\":\"Sample SACCO\"}]}");
            } else if (path.endsWith("/companies(" + COMPANY + ")/customers")) {
                send(ex, 200, "{\"@odata.count\":8,\"value\":[]}");
            } else if (path.endsWith("/companies(" + COMPANY + ")/vendors")) {
                send(ex, 200, "{\"@odata.count\":5,\"value\":[]}");
            } else {
                send(ex, 404, "{\"error\":{\"code\":\"BadRequest_NotFound\",\"message\":\"The request URI is not valid.\"}}");
            }
        });
        bc.start();
    }

    @AfterEach
    void tearDown() {
        bc.stop(0);
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private IntegrationSetup bcSetup() {
        IntegrationSetup s = fx.integration.setup();
        String base = "http://127.0.0.1:" + bc.getAddress().getPort();
        s.setTenantId("tenant-a");
        s.setEnvironment("Sandbox");
        s.setCompanyId(COMPANY);
        s.setClientId("app-1");
        s.setTokenUrl(base + "/{tenantId}/oauth2/v2.0/token");
        s.setApiBaseUrl(base + "/v2.0/{tenantId}/{environment}");
        s.setTimeoutSeconds(5);
        return s;
    }

    // ------------------------------------------------------------------ helpers

    private Path sample(String set, String name) {
        return Samples.dir().resolve(set).resolve("Sample " + name + " Data Templates.xlsx");
    }

    private void submitClean() throws IOException {
        Submission.View s = fx.intake.openSubmission(run.id(), "harry");
        for (String[] f : new String[][] {{"Static", "STATIC"}, {"HR", "HR"}, {"Transactional", "TRANSACTIONAL"}}) {
            try (InputStream in = Files.newInputStream(sample("clean", f[0]))) {
                fx.intake.addFile(s.id(), TemplateKind.valueOf(f[1]), "Sample " + f[0] + ".xlsx", in, "harry");
            }
        }
        fx.intake.process(s.id(), "harry");
    }

    private IntegrationService.Output build(String set, String name, TemplateKind kind) throws IOException {
        try (InputStream in = Files.newInputStream(sample(set, name))) {
            return fx.integration.buildPackage(run.id(), kind, "Sample " + name + ".xlsx", in, "harry");
        }
    }

    /** Relationship targets inside an .xlsx that point to parts missing from the zip (Excel would "repair" the file). */
    static List<String> brokenRelationships(byte[] xlsx) throws IOException {
        java.util.Map<String, byte[]> parts = new java.util.HashMap<>();
        try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (java.util.zip.ZipEntry e; (e = z.getNextEntry()) != null; ) parts.put(e.getName(), z.readAllBytes());
        }
        List<String> broken = new ArrayList<>();
        java.util.regex.Pattern target = java.util.regex.Pattern.compile("Target=\"([^\"]+)\"(?![^>]*TargetMode=\"External\")");
        for (var e : parts.entrySet()) {
            if (!e.getKey().endsWith(".rels")) continue;
            String rels = e.getKey();
            String owner = rels.replace("_rels/", "").replaceAll("\\.rels$", "");
            if (!owner.isEmpty() && !rels.equals("_rels/.rels") && !parts.containsKey(owner)) {
                broken.add(rels + " belongs to a missing part");
                continue;
            }
            String base = rels.equals("_rels/.rels") ? "" : owner.substring(0, owner.lastIndexOf('/') + 1);
            java.util.regex.Matcher m = target.matcher(new String(e.getValue(), StandardCharsets.UTF_8));
            while (m.find()) {
                String t = m.group(1);
                // Zip entry names always use '/': resolve as a URI, never as a file path (backslashes on Windows).
                String p = java.net.URI.create("/" + base).resolve(t).normalize().getPath().substring(1);
                if (!parts.containsKey(p)) broken.add(rels + " -> " + t);
            }
        }
        return broken;
    }

    private static int pos(List<PackageJob.PackageTable> ts, String sheet) {
        return ts.stream().filter(t -> t.sheet().equals(sheet)).findFirst().orElseThrow().order();
    }

    // ------------------------------------------------------------------ packages

    @Test
    void aValidatedCleanWorkbookBecomesAPackageInImportOrder() throws Exception {
        submitClean();
        IntegrationService.Output out = build("clean", "Static", TemplateKind.STATIC);
        List<PackageJob.PackageTable> ts = out.job().tables();
        assertTrue(pos(ts, "Members") < pos(ts, "Nominee & BBF"));
        assertTrue(pos(ts, "Members") < pos(ts, "Savings Accounts"));
        assertTrue(pos(ts, "Loans") < pos(ts, "Loan Guarantors and Security"));
        assertTrue(pos(ts, "Customer Employer_Tenant") < pos(ts, "Members"), "members refer to their employer");
        assertTrue(pos(ts, "Standing Order Header") < pos(ts, "Standing Order Lines"), "lines after their header");
        assertEquals(150, ts.stream().filter(t -> t.sheet().equals("Members")).findFirst().orElseThrow().rows());
        assertTrue(ts.stream().allMatch(t -> t.tableId() != null), "only sheets with a Business Central table");
        assertEquals("Sample Static (import package).xlsx", out.fileName());

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.workbook()))) {
            assertEquals("Import order", wb.getSheetName(0));
            for (PackageJob.PackageTable t : ts) assertEquals(t.sheet(), wb.getSheetName(t.order()));
            assertEquals(ts.size() + 1, wb.getNumberOfSheets());
            assertEquals("STATIC DATA", wb.getSheet("Members").getRow(0).getCell(0).getStringCellValue(), "BC package header kept");
        }
        assertEquals(List.of(), brokenRelationships(out.workbook()), "every part a relationship points to exists");
        List<ImportRecord> imports = fx.integration.imports(run.id());
        assertEquals(ts.size(), imports.size());
        assertTrue(imports.stream().allMatch(r -> r.status() == ImportRecord.Status.PENDING));
        try (Stream<Path> left = Files.list(fx.workspaceRoot)) {
            assertEquals(0, left.count(), "no client files left");
        }
    }

    @Test
    void openCriticalFindingsInThatWorkbookBlockItsPackage() throws Exception {
        submitClean(); // HR has 48 Critical findings (the template's Integer phone columns)
        ValidationException e = assertThrows(ValidationException.class, () -> build("clean", "HR", TemplateKind.HR));
        assertTrue(e.getMessage().contains("48 Critical finding(s) in the HR workbook"), e.getMessage());
        build("clean", "Static", TemplateKind.STATIC); // HR's findings do not block the Static package
        ValidationException tx = assertThrows(ValidationException.class, () -> build("clean", "Transactional", TemplateKind.TRANSACTIONAL));
        assertTrue(tx.getMessage().contains("No sheet of this workbook names a Business Central table"),
                "opening balances are posted through journals, not configuration tables");
    }

    @Test
    void onlyTheFileThatWasValidatedCanBePackaged() throws Exception {
        assertThrows(ValidationException.class, () -> build("clean", "Static", TemplateKind.STATIC), "no check yet");
        submitClean();
        ValidationException e = assertThrows(ValidationException.class, () -> build("with-errors", "Static", TemplateKind.STATIC));
        assertTrue(e.getMessage().contains("not the STATIC workbook validated in check 1"), e.getMessage());
    }

    @Test
    void theGateCanBeSwitchedOffButSaysSoInThePackage() throws Exception {
        IntegrationSetup s = fx.integration.setup();
        s.setRequireCleanCheck(false);
        fx.integration.putSetup(s, "admin");
        IntegrationService.Output out = build("with-errors", "Static", TemplateKind.STATIC);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.workbook()))) {
            assertTrue(wb.getSheet("Import order").getRow(2).getCell(1).getStringCellValue().contains("No clean check required"));
        }
    }

    @Test
    void importsAreRecordedAgainstThePackage() throws Exception {
        submitClean();
        build("clean", "Static", TemplateKind.STATIC);
        ImportRecord ok = fx.integration.recordImport(run.id(), "Members", 150, false, null, "harry");
        assertEquals(ImportRecord.Status.IMPORTED, ok.status());
        ImportRecord part = fx.integration.recordImport(run.id(), "Loans", 80, false, "5 rejected: missing loan product", "harry");
        assertEquals(ImportRecord.Status.PARTIAL, part.status());
        assertEquals(ImportRecord.Status.FAILED, fx.integration.recordImport(run.id(), "Item", null, true, "Locked", "harry").status());
        assertThrows(ValidationException.class, () -> fx.integration.recordImport(run.id(), "Members", null, false, null, "harry"));
        assertThrows(NotFoundException.class, () -> fx.integration.recordImport(run.id(), "Nope", 1, false, null, "harry"));
    }

    // ------------------------------------------------------------------ Business Central API

    @Test
    void connectionTestSignsInWithClientCredentialsAndListsCompanies() {
        fx.integration.putSetup(bcSetup(), "admin");
        IntegrationService.Connection none = fx.integration.testConnection("admin");
        assertFalse(none.ok());
        assertTrue(none.message().contains("DATABRIDGE_BC_CLIENT_SECRET"), none.message());
        assertFalse(fx.integration.secretPresent());

        fx.env.put("DATABRIDGE_BC_CLIENT_SECRET", "wrong");
        IntegrationService.Connection bad = fx.integration.testConnection("admin");
        assertFalse(bad.ok());
        assertEquals("Sign-in to Microsoft Entra ID failed: AADSTS7000215: Invalid client secret provided.", bad.message());

        fx.env.put("DATABRIDGE_BC_CLIENT_SECRET", "s3cret");
        IntegrationService.Connection good = fx.integration.testConnection("admin");
        assertTrue(good.ok(), good.message());
        assertEquals("Sample SACCO", good.companies().get(0).displayName());
        assertEquals("Bearer tok-1", seenAuth.get(seenAuth.size() - 1));
        assertTrue(fx.auditRepo.list(null, null, 100).stream().noneMatch(a -> String.valueOf(a.details()).contains("s3cret")),
                "the secret never reaches the audit trail");
    }

    @Test
    void verifyReadsCountsBackAndFlagsMismatches() throws Exception {
        IntegrationSetup s = bcSetup();
        s.setEntities(new ArrayList<>(List.of(new EntityMap("Customer Employer_Tenant", "api/v2.0", "customers"),
                new EntityMap("Vendor_Debt Collectors", "/api/v2.0/", "vendors"),
                new EntityMap("Item", "api/v2.0", "items")))); // the fake has no items page
        fx.integration.putSetup(s, "admin");
        fx.env.put("DATABRIDGE_BC_CLIENT_SECRET", "s3cret");
        submitClean();
        build("clean", "Static", TemplateKind.STATIC);

        List<ImportRecord> out = fx.integration.verify(run.id(), "harry");
        ImportRecord cust = out.stream().filter(r -> r.sheet().equals("Customer Employer_Tenant")).findFirst().orElseThrow();
        ImportRecord vend = out.stream().filter(r -> r.sheet().equals("Vendor_Debt Collectors")).findFirst().orElseThrow();
        assertEquals(ImportRecord.Status.MATCHED, cust.status());
        assertEquals(8L, cust.bcCount());
        assertEquals(ImportRecord.Status.MISMATCH, vend.status());
        assertEquals("Business Central holds 5 vendors; the package had 8", vend.note());
        assertEquals(ImportRecord.Status.PENDING,
                out.stream().filter(r -> r.sheet().equals("Members")).findFirst().orElseThrow().status(), "unmapped tables untouched");
        ImportRecord item = out.stream().filter(r -> r.sheet().equals("Item")).findFirst().orElseThrow();
        assertEquals(ImportRecord.Status.PENDING, item.status(), "a table that cannot be counted keeps its status");
        assertTrue(item.note().startsWith("Could not count in Business Central: Not found"), item.note());
        assertEquals(1, tokens.get(), "the token is reused while valid");

        fx.env.put("DATABRIDGE_BC_CLIENT_SECRET", "wrong");
        assertThrows(com.databridge.core.integration.IntegrationException.class, () -> fx.integration.verify(run.id(), "harry"),
                "a sign-in failure stops verification");
    }

    @Test
    void setupRefusesUnsafeValues() {
        IntegrationSetup s = bcSetup();
        s.setApiBaseUrl("http://bc.example.com/v2.0");
        assertThrows(ValidationException.class, () -> fx.integration.putSetup(s, "admin"), "plain http off this machine");
        IntegrationSetup t = bcSetup();
        t.setTenantId("../evil");
        assertThrows(ValidationException.class, () -> fx.integration.putSetup(t, "admin"));
        IntegrationSetup u = bcSetup();
        u.setEntities(new ArrayList<>(List.of(new EntityMap("Members", "api/../../x", "members"))));
        assertThrows(ValidationException.class, () -> fx.integration.putSetup(u, "admin"));
        IntegrationSetup v = bcSetup();
        v.setClientSecretEnv("my secret");
        assertThrows(ValidationException.class, () -> fx.integration.putSetup(v, "admin"));
    }
}
