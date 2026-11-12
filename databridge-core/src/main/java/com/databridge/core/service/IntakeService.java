package com.databridge.core.service;

import com.databridge.core.domain.DatasetVersion;
import com.databridge.core.domain.MigrationRun;
import com.databridge.core.domain.RunState;
import com.databridge.core.domain.TemplateKind;
import com.databridge.core.port.DatasetRepository;
import com.databridge.core.run.Outcome;
import com.databridge.core.run.RunStatus;
import com.databridge.core.schema.SchemaExtractor;
import com.databridge.core.schema.WorkbookSchema;
import com.databridge.core.setup.DataBridgeSetup;
import com.databridge.core.setup.EffectiveSetup;
import com.databridge.core.workspace.Workspace;
import com.databridge.core.workspace.WorkspaceManagers;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Takes in a template workbook for a run: streams it into a workspace (enforcing the
 * upload limit and computing its SHA-256), builds its schema, records a Dataset Version
 * holding metadata and structure only, audits it, and lets the workspace be destroyed.
 * A failure is recorded as an ERROR version; it is never reported as a pass.
 */
public final class IntakeService {

    private static final int MAX_FILE_NAME = 260;
    private static final int MAX_ERROR = 1000;

    private final MigrationService migrations;
    private final DatasetRepository datasets;
    private final SetupService setups;
    private final WorkspaceManagers workspaces;
    private final AuditLog audit;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public IntakeService(MigrationService migrations, DatasetRepository datasets, SetupService setups,
                         WorkspaceManagers workspaces, AuditLog audit, Clock clock) {
        this.migrations = migrations;
        this.datasets = datasets;
        this.setups = setups;
        this.workspaces = workspaces;
        this.audit = audit;
        this.clock = clock;
    }

    public IntakeResult intake(String runId, TemplateKind kind, String fileName, InputStream content, String actor) {
        String who = AuditLog.actor(actor);
        MigrationRun run = migrations.run(runId);
        if (run.state() != RunState.OPEN) throw new ValidationException("Run " + run.runNo() + " is closed");
        if (kind == null) throw new ValidationException("Template kind is required (STATIC, TRANSACTIONAL, HR or OTHER)");
        String name = displayName(fileName);
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".xlsx") && !lower.endsWith(".xlsm")) {
            throw new ValidationException("Only .xlsx or .xlsm workbooks are accepted");
        }

        EffectiveSetup setup = setups.effective();
        DataBridgeSetup g = setup.general();
        long limit = (long) g.getMaxUploadMb() * 1024 * 1024;
        SchemaExtractor extractor = new SchemaExtractor(setup.templates(), g.getCaptionScanRows());
        long[] size = {0};
        String[] sha = {null};

        Outcome<WorkbookSchema> outcome = Outcome.run("intake", () -> {
            try (Workspace ws = workspaces.forSetup(g).open()) {
                Path file = ws.file("upload.xlsx");
                sha[0] = copyLimited(content, file, limit, size);
                WorkbookSchema s = extractor.extract(file);
                return new WorkbookSchema(name, s.sheets());
            }
        });

        int version = datasets.maxVersion(run.id(), kind) + 1;
        WorkbookSchema schema = outcome.isSuccess() ? outcome.value() : null;
        DatasetVersion d = new DatasetVersion(
                UUID.randomUUID().toString(), run.id(), kind, version, name,
                sha[0], size[0],
                schema == null ? 0 : schema.sheets().size(),
                schema == null ? 0 : schema.sheets().stream().mapToInt(t -> t.warnings().size()).sum(),
                outcome.status(),
                outcome.error().map(e -> e.length() > MAX_ERROR ? e.substring(0, MAX_ERROR) : e).orElse(null),
                schema == null ? null : toJson(schema),
                clock.instant().truncatedTo(ChronoUnit.MILLIS), who);
        datasets.insert(d);

        String summary = kind + " v" + version + ": " + (outcome.status() == RunStatus.SUCCEEDED
                ? d.sheetCount() + " sheet(s), " + d.warningCount() + " warning(s), " + d.sizeBytes() + " bytes, sha256 "
                  + shortHash(d.sha256())
                : "ERROR " + d.error());
        audit.record(who, "DATASET_UPLOADED", "DATASET", d.id(), summary);
        return new IntakeResult(d, schema);
    }

    public List<DatasetVersion> datasets(String runId) {
        return datasets.listByRun(migrations.run(runId).id());
    }

    public DatasetVersion dataset(String id) {
        return datasets.find(id).orElseThrow(() -> new NotFoundException("Dataset", id));
    }

    /** Copies at most {@code limit} bytes into {@code target}; returns the SHA-256 hex. */
    static String copyLimited(InputStream in, Path target, long limit, long[] size) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] buf = new byte[64 * 1024];
        try (DigestInputStream din = new DigestInputStream(in, md); OutputStream out = Files.newOutputStream(target)) {
            int n;
            while ((n = din.read(buf)) != -1) {
                size[0] += n;
                if (size[0] > limit) {
                    throw new ValidationException("Workbook is larger than the " + (limit / (1024 * 1024)) + " MB limit");
                }
                out.write(buf, 0, n);
            }
        }
        if (size[0] == 0) throw new ValidationException("The uploaded file is empty");
        return HexFormat.of().formatHex(md.digest());
    }

    private String toJson(WorkbookSchema s) {
        try {
            return json.writeValueAsString(s);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot serialise schema", e);
        }
    }

    private static String displayName(String fileName) {
        String n = fileName == null ? "" : fileName.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).trim();
        if (n.isEmpty()) throw new ValidationException("A file name is required");
        return n.length() > MAX_FILE_NAME ? n.substring(n.length() - MAX_FILE_NAME) : n;
    }

    private static String shortHash(String h) {
        return h == null || h.length() < 8 ? String.valueOf(h) : h.substring(0, 4) + "..." + h.substring(h.length() - 4);
    }
}
