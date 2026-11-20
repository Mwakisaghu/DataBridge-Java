package com.databridge.core.service;

import com.databridge.core.domain.DatasetVersion;
import com.databridge.core.domain.FindingFilter;
import com.databridge.core.domain.ImportRecord;
import com.databridge.core.domain.MigrationRun;
import com.databridge.core.domain.PackageJob;
import com.databridge.core.domain.RunState;
import com.databridge.core.domain.TemplateKind;
import com.databridge.core.domain.ValidationCheck;
import com.databridge.core.finding.FindingStatus;
import com.databridge.core.finding.Severity;
import com.databridge.core.integration.BcClient;
import com.databridge.core.integration.EntityMap;
import com.databridge.core.integration.ImportOrder;
import com.databridge.core.integration.IntegrationException;
import com.databridge.core.integration.IntegrationSetup;
import com.databridge.core.port.CheckRepository;
import com.databridge.core.port.DatasetRepository;
import com.databridge.core.port.FindingRepository;
import com.databridge.core.port.IntegrationRepository;
import com.databridge.core.schema.SchemaExtractor;
import com.databridge.core.schema.TableSchema;
import com.databridge.core.schema.WorkbookSchema;
import com.databridge.core.setup.DataBridgeSetup;
import com.databridge.core.setup.EffectiveSetup;
import com.databridge.core.setup.SetupException;
import com.databridge.core.setup.SetupLoader;
import com.databridge.core.setup.TemplateSetup;
import com.databridge.core.validation.SheetData;
import com.databridge.core.validation.WorkbookReader;
import com.databridge.core.workspace.Workspace;
import com.databridge.core.workspace.WorkspaceManagers;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Module 5: building import packages in dependency order from validated workbooks, tracking their
 * import into Business Central and reading record counts back through the Business Central API.
 */
public final class IntegrationService {

    /** The package file (to send, never to store) and its job. */
    public record Output(PackageJob job, byte[] workbook, String fileName, List<String> notes) { }

    /** The result of a connection test. */
    public record Connection(boolean ok, String message, List<BcClient.Company> companies) { }

    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");
    private static final Pattern ENTITY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,99}$");
    private static final Pattern API_PATH = Pattern.compile("^[A-Za-z0-9._/-]{1,200}$");
    private static final Pattern ENV_NAME = Pattern.compile("^[A-Z][A-Z0-9_]{0,99}$");
    static final String ORDER_SHEET = "Import order";

    private final IntegrationRepository repo;
    private final MigrationService migrations;
    private final SetupService setups;
    private final RuleService rules;
    private final CheckRepository checks;
    private final FindingRepository findings;
    private final DatasetRepository datasets;
    private final WorkspaceManagers workspaces;
    private final AuditLog audit;
    private final Clock clock;
    private final Function<String, String> env;
    private final IntegrationSetup defaults;

    public IntegrationService(IntegrationRepository repo, MigrationService migrations, SetupService setups, RuleService rules,
                              CheckRepository checks, FindingRepository findings, DatasetRepository datasets,
                              WorkspaceManagers workspaces, AuditLog audit, Clock clock, Function<String, String> env) {
        this.repo = repo;
        this.migrations = migrations;
        this.setups = setups;
        this.rules = rules;
        this.checks = checks;
        this.findings = findings;
        this.datasets = datasets;
        this.workspaces = workspaces;
        this.audit = audit;
        this.clock = clock;
        this.env = env;
        this.defaults = loadDefaults();
    }

    // ------------------------------------------------------------------ setup

    public IntegrationSetup setup() {
        return repo.setup().orElseGet(() -> copy(defaults));
    }

    /** Whether the secret's environment variable is set; the value is never returned. */
    public boolean secretPresent() {
        String v = env.apply(setup().getClientSecretEnv());
        return v != null && !v.isBlank();
    }

    public IntegrationSetup putSetup(IntegrationSetup s, String actor) {
        String who = AuditLog.actor(actor);
        validate(s);
        repo.saveSetup(s, who, now());
        audit.record(who, "INTEGRATION_SETUP_CHANGED", "SETUP", "integration",
                "Tenant " + (s.getTenantId() == null ? "-" : s.getTenantId()) + ", environment " + s.getEnvironment() + ", "
                        + s.getEntities().size() + " entity map(s), clean check " + (s.isRequireCleanCheck() ? "required" : "NOT required"));
        return s;
    }

    static void validate(IntegrationSetup s) {
        if (s == null) throw new ValidationException("The Integration Setup is required");
        for (String[] f : new String[][] {{"tenantId", s.getTenantId()}, {"environment", s.getEnvironment()},
                {"companyId", s.getCompanyId()}, {"clientId", s.getClientId()}}) {
            if (f[1] != null && !f[1].isBlank() && !SAFE.matcher(f[1]).matches()) {
                throw new ValidationException(f[0] + " may only hold letters, digits, '.', '-' and '_'");
            }
        }
        if (s.getClientSecretEnv() == null || !ENV_NAME.matcher(s.getClientSecretEnv()).matches()) {
            throw new ValidationException("clientSecretEnv must name an environment variable, e.g. DATABRIDGE_BC_CLIENT_SECRET");
        }
        url("tokenUrl", s.getTokenUrl());
        url("apiBaseUrl", s.getApiBaseUrl());
        if (s.getScope() == null || s.getScope().isBlank()) throw new ValidationException("scope is required");
        if (s.getTimeoutSeconds() < 1 || s.getTimeoutSeconds() > 300) throw new ValidationException("timeoutSeconds must be 1 to 300");
        for (EntityMap e : s.getEntities()) {
            if (e.getSheet() == null || e.getSheet().isBlank()) throw new ValidationException("Every entity map needs a sheet");
            if (e.getEntitySet() == null || !ENTITY.matcher(e.getEntitySet()).matches()) {
                throw new ValidationException(e.getSheet() + ": entitySet must be an API entity set name, e.g. customers");
            }
            if (e.getApiPath() == null || !API_PATH.matcher(e.getApiPath()).matches() || e.getApiPath().contains("..")) {
                throw new ValidationException(e.getSheet() + ": apiPath must look like api/v2.0 or api/publisher/group/v1.0");
            }
        }
    }

    /** Business Central URLs must be https; plain http only to this machine (a local test server). */
    private static void url(String name, String template) {
        if (template == null || template.isBlank()) throw new ValidationException(name + " is required");
        URI u;
        try {
            u = URI.create(template.replace("{tenantId}", "t").replace("{environment}", "e"));
        } catch (IllegalArgumentException e) {
            throw new ValidationException(name + " is not a valid URL");
        }
        String host = u.getHost() == null ? "" : u.getHost();
        boolean local = host.equals("localhost") || host.equals("127.0.0.1");
        if (!"https".equals(u.getScheme()) && !(local && "http".equals(u.getScheme()))) {
            throw new ValidationException(name + " must use https");
        }
    }

    public Connection testConnection(String actor) {
        String who = AuditLog.actor(actor);
        try {
            List<BcClient.Company> cs = client().companies();
            IntegrationSetup s = setup();
            boolean found = cs.stream().anyMatch(c -> c.id().equalsIgnoreCase(String.valueOf(s.getCompanyId())));
            String msg = "Connected to " + s.getEnvironment() + ": " + cs.size() + " compan" + (cs.size() == 1 ? "y" : "ies")
                    + (found ? ", including the configured company" : "; the configured company id is not among them");
            audit.record(who, "INTEGRATION_TESTED", "SETUP", "integration", msg);
            return new Connection(found, msg, cs);
        } catch (IntegrationException e) {
            audit.record(who, "INTEGRATION_TESTED", "SETUP", "integration", "Failed: " + e.getMessage());
            return new Connection(false, e.getMessage(), List.of());
        }
    }

    private BcClient client() {
        return new BcClient(setup(), env, clock);
    }

    // ------------------------------------------------------------------ packages

    /** The import order of every sheet the Template and Rule Setups know. */
    public ImportOrder.Result importOrder(List<String> sheets) {
        return ImportOrder.of(sheets, rules.effective());
    }

    /**
     * Builds the import package of one validated workbook: only sheets with a Business Central table,
     * in import order, after an "Import order" sheet. The workbook must be the one validated in the
     * run's latest check, and that check must have no open Critical finding (unless the setup says
     * otherwise). The package is returned, not kept.
     */
    public Output buildPackage(String runId, TemplateKind kind, String fileName, InputStream content, String actor) {
        String who = AuditLog.actor(actor);
        if (kind == null) throw new ValidationException("Template kind is required (STATIC, TRANSACTIONAL, HR or OTHER)");
        String name = IntakeService.displayName(fileName);
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".xlsx") && !lower.endsWith(".xlsm")) throw new ValidationException("Only .xlsx or .xlsm workbooks are accepted");
        MigrationRun run = migrations.run(runId);
        if (run.state() != RunState.OPEN) throw new ValidationException("Run " + run.runNo() + " is closed");
        IntegrationSetup integ = setup();
        EffectiveSetup setup = setups.effective();
        DataBridgeSetup g = setup.general();
        long limit = (long) g.getMaxUploadMb() * 1024 * 1024;

        try (Workspace ws = workspaces.forSetup(g).open()) {
            Path in = ws.file("in.xlsx");
            long[] size = {0};
            String sha;
            try {
                sha = IntakeService.copyLimited(content, in, limit, size);
            } catch (IOException e) {
                throw new ValidationException("The upload was interrupted: " + e.getMessage());
            }
            ValidationCheck check = gate(run, kind, sha, integ.isRequireCleanCheck());
            try {
                WorkbookSchema schema = new SchemaExtractor(setup.templates(), g.getCaptionScanRows()).extract(in);
                Map<String, Integer> rowsBySheet = new LinkedHashMap<>();
                for (SheetData sd : WorkbookReader.read(in, schema, kind)) rowsBySheet.put(sd.name(), sd.rows().size());
                List<TableSchema> tables = schema.sheets().stream().filter(t -> t.tableId() != null).toList();
                if (tables.isEmpty()) throw new ValidationException("No sheet of this workbook names a Business Central table");
                ImportOrder.Result order = importOrder(tables.stream().map(TableSchema::sheetName).toList());

                List<PackageJob.PackageTable> out = new ArrayList<>();
                byte[] bytes;
                try (InputStream fin = Files.newInputStream(in); Workbook wb = WorkbookFactory.create(fin)) {
                    // Create the order sheet before removing any: POI leaves a removed sheet's relationships
                    // behind, and a sheet created afterwards could reuse its part name and inherit them.
                    wb.createSheet(ORDER_SHEET);
                    // Keep only table sheets, in import order, after the order sheet.
                    for (int i = wb.getNumberOfSheets() - 1; i >= 0; i--) {
                        String sn = wb.getSheetName(i);
                        if (!sn.equals(ORDER_SHEET) && order.sheets().stream().noneMatch(s -> s.equals(sn))) wb.removeSheetAt(i);
                    }
                    int pos = 1;
                    for (String sn : order.sheets()) {
                        wb.setSheetOrder(sn, pos - 1);
                        TableSchema t = tables.stream().filter(x -> x.sheetName().equals(sn)).findFirst().orElseThrow();
                        out.add(new PackageJob.PackageTable(pos, sn, t.tableName(), t.tableId(), rowsBySheet.getOrDefault(sn, 0)));
                        pos++;
                    }
                    writeOrderSheet(wb, run, check, kind, out, order.notes());
                    wb.setActiveSheet(0);
                    wb.setSelectedTab(0);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    wb.write(bos);
                    bytes = bos.toByteArray();
                }
                PackageJob job = new PackageJob(UUID.randomUUID().toString(), run.id(), check == null ? null : check.id(), kind, name,
                        sha, sha256(bytes), out, now(), who);
                repo.insertPackage(job);
                for (PackageJob.PackageTable t : out) {
                    repo.upsertImport(new ImportRecord(run.id(), t.sheet(), t.tableId(), t.rows(), null, null,
                            ImportRecord.Status.PENDING, null, now(), who));
                }
                audit.record(who, "PACKAGE_BUILT", "PACKAGE", job.id(), kind + " " + name + ": " + out.size() + " table(s), "
                        + out.stream().mapToInt(PackageJob.PackageTable::rows).sum() + " row(s)"
                        + (check == null ? ", clean check NOT required" : ", from check " + check.checkNo()) + "; file returned, not kept");
                return new Output(job, bytes, packageName(name), order.notes());
            } catch (IOException | RuntimeException e) {
                if (e instanceof ValidationException v) throw v;
                throw new ValidationException("The package could not be built: " + e.getMessage());
            }
        }
    }

    /** The latest check of the run, when the workbook is the one it validated and nothing Critical is open. */
    private ValidationCheck gate(MigrationRun run, TemplateKind kind, String sha, boolean required) {
        List<ValidationCheck> cs = checks.listByRun(run.id());
        if (cs.isEmpty()) {
            if (required) throw new ValidationException("Validate this run's workbooks first: there is no check yet");
            return null;
        }
        ValidationCheck latest = cs.get(0);
        boolean same = false;
        for (String id : latest.datasetIds() == null ? new String[0] : latest.datasetIds().split(",")) {
            Optional<DatasetVersion> d = datasets.find(id.trim());
            if (d.isPresent() && d.get().kind() == kind && sha.equalsIgnoreCase(d.get().sha256())) same = true;
        }
        if (!required) return same ? latest : null;
        if (!same) {
            throw new ValidationException("This file is not the " + kind + " workbook validated in check " + latest.checkNo()
                    + ". Submit it in Intake & validation first, then build the package from the same file.");
        }
        // Only this workbook's Critical findings block its package; run-level findings (no workbook) block every package.
        long open = 0;
        for (FindingStatus st : new FindingStatus[] {FindingStatus.OPEN, FindingStatus.WAIVER_REQUESTED}) {
            open += findings.list(latest.id(), new FindingFilter(Severity.CRITICAL, st, null, null), 0, Integer.MAX_VALUE).stream()
                    .filter(f -> f.kind() == null || f.kind().equals(kind.name())).count();
        }
        if (open > 0) {
            throw new ValidationException("Check " + latest.checkNo() + " still has " + open + " Critical finding(s) in the " + kind
                    + " workbook that are neither fixed nor waived. Resolve them before building its package.");
        }
        return latest;
    }

    private static void writeOrderSheet(Workbook wb, MigrationRun run, ValidationCheck check, TemplateKind kind,
                                        List<PackageJob.PackageTable> tables, List<String> notes) {
        Sheet s = wb.getSheet(ORDER_SHEET);
        wb.setSheetOrder(ORDER_SHEET, 0);
        int r = 0;
        row(s, r++, "DataBridge import package", null, null, null);
        row(s, r++, "Run", (run.type().name().equals("CUTOVER") ? "Cutover" : "Mock") + " run " + run.runNo() + ", cutoff " + run.cutoffDate(), null, null);
        row(s, r++, "Validated in", check == null ? "No clean check required by the Integration Setup" : "Check " + check.checkNo(), null, null);
        row(s, r++, "Template", kind.name(), null, null);
        r++;
        row(s, r++, "Order", "Sheet", "Business Central table", "Table ID");
        for (PackageJob.PackageTable t : tables) {
            Row x = row(s, r++, String.valueOf(t.order()), t.sheet(), t.tableName(), t.tableId() == null ? "" : String.valueOf(t.tableId()));
            x.createCell(4).setCellValue(t.rows());
        }
        s.getRow(5).createCell(4).setCellValue("Rows");
        r++;
        row(s, r++, "Import the sheets in this order: each comes after the sheets it refers to.", null, null, null);
        for (String n : notes) row(s, r++, n, null, null, null);
        s.setColumnWidth(0, 10 * 256);
        s.setColumnWidth(1, 36 * 256);
        s.setColumnWidth(2, 36 * 256);
        s.setColumnWidth(3, 12 * 256);
        s.setColumnWidth(4, 10 * 256);
    }

    private static Row row(Sheet s, int i, String a, String b, String c, String d) {
        Row r = s.createRow(i);
        String[] v = {a, b, c, d};
        for (int k = 0; k < v.length; k++) if (v[k] != null) r.createCell(k).setCellValue(v[k]);
        return r;
    }

    public List<PackageJob> packages(String runId) {
        return repo.packages(migrations.run(runId).id());
    }

    // ------------------------------------------------------------------ import tracking

    public List<ImportRecord> imports(String runId) {
        return repo.imports(migrations.run(runId).id());
    }

    /** The consultant records what Business Central reported for one table's import. */
    public ImportRecord recordImport(String runId, String sheet, Integer importedRows, boolean failed, String note, String actor) {
        String who = AuditLog.actor(actor);
        ImportRecord cur = find(runId, sheet);
        if (!failed && (importedRows == null || importedRows < 0)) throw new ValidationException("Give the number of rows imported");
        if (note != null && note.length() > 500) throw new ValidationException("The note is longer than 500 characters");
        ImportRecord.Status st = failed ? ImportRecord.Status.FAILED
                : importedRows == cur.expectedRows() ? ImportRecord.Status.IMPORTED : ImportRecord.Status.PARTIAL;
        ImportRecord u = new ImportRecord(cur.runId(), cur.sheet(), cur.tableId(), cur.expectedRows(), importedRows, cur.bcCount(), st,
                note == null || note.isBlank() ? null : note.trim(), now(), who);
        repo.upsertImport(u);
        audit.record(who, "IMPORT_RECORDED", "IMPORT", cur.runId() + "/" + cur.sheet(),
                cur.sheet() + ": " + st + (importedRows == null ? "" : ", " + importedRows + " of " + cur.expectedRows() + " row(s)"));
        return u;
    }

    /** Reads the record count of every mapped table back from Business Central. */
    public List<ImportRecord> verify(String runId, String actor) {
        String who = AuditLog.actor(actor);
        IntegrationSetup s = setup();
        BcClient bc = client();
        List<ImportRecord> out = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (ImportRecord r : imports(runId)) {
            Optional<EntityMap> map = s.getEntities().stream()
                    .filter(e -> TemplateSetup.normalise(e.getSheet()).equals(TemplateSetup.normalise(r.sheet()))).findFirst();
            if (map.isEmpty()) {
                out.add(r);
                continue;
            }
            long n;
            try {
                n = bc.count(map.get());
            } catch (IntegrationException e) {
                // Sign-in or connection problems stop everything (nothing could be counted); a table
                // Business Central cannot count is reported on that table and the rest carry on.
                if (checked == 0 && failures.isEmpty() && !e.getMessage().startsWith("Not found")
                        && !e.getMessage().startsWith("Business Central did not return a count")) {
                    throw e;
                }
                failures.add(r.sheet());
                ImportRecord u = new ImportRecord(r.runId(), r.sheet(), r.tableId(), r.expectedRows(), r.importedRows(), null, r.status(),
                        "Could not count in Business Central: " + e.getMessage(), now(), who);
                repo.upsertImport(u);
                out.add(u);
                continue;
            }
            checked++;
            ImportRecord.Status st = n == r.expectedRows() ? ImportRecord.Status.MATCHED : ImportRecord.Status.MISMATCH;
            String note = st == ImportRecord.Status.MATCHED ? null
                    : "Business Central holds " + n + " " + map.get().getEntitySet() + "; the package had " + r.expectedRows();
            ImportRecord u = new ImportRecord(r.runId(), r.sheet(), r.tableId(), r.expectedRows(), r.importedRows(), n, st,
                    note == null ? r.note() : note, now(), who);
            repo.upsertImport(u);
            out.add(u);
        }
        if (checked == 0 && !failures.isEmpty()) {
            throw new IntegrationException("Business Central could not count any mapped table; check the entity maps and API pages ("
                    + String.join(", ", failures) + ")");
        }
        audit.record(who, "IMPORT_VERIFIED", "RUN", runId, checked + " table(s) counted in Business Central: "
                + out.stream().filter(r -> r.status() == ImportRecord.Status.MISMATCH).count() + " mismatch(es)"
                + (failures.isEmpty() ? "" : ", " + failures.size() + " could not be counted"));
        return out;
    }

    private ImportRecord find(String runId, String sheet) {
        return imports(runId).stream().filter(r -> r.sheet().equals(sheet)).findFirst()
                .orElseThrow(() -> new NotFoundException("Import of sheet", sheet));
    }

    // ------------------------------------------------------------------ helpers

    static String packageName(String name) {
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(0, dot)) + " (import package).xlsx";
    }

    private static IntegrationSetup loadDefaults() {
        try (InputStream in = IntegrationService.class.getResourceAsStream("/setup/integration/default.json")) {
            if (in == null) return new IntegrationSetup();
            return SetupLoader.mapper().readValue(in, IntegrationSetup.class);
        } catch (IOException e) {
            throw new SetupException("Cannot read the default Integration Setup: " + e.getMessage(), e);
        }
    }

    private static IntegrationSetup copy(IntegrationSetup s) {
        return SetupLoader.mapper().convertValue(SetupLoader.mapper().valueToTree(s), IntegrationSetup.class);
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
