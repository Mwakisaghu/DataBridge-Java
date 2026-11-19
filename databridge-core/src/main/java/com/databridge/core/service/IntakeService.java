package com.databridge.core.service;

import com.databridge.core.domain.DatasetVersion;
import com.databridge.core.domain.MigrationRun;
import com.databridge.core.domain.RunState;
import com.databridge.core.domain.StoredFinding;
import com.databridge.core.domain.TemplateKind;
import com.databridge.core.domain.ValidationCheck;
import com.databridge.core.finding.Finding;
import com.databridge.core.finding.FindingStatus;
import com.databridge.core.finding.Severity;
import com.databridge.core.finding.ValueMasker;
import com.databridge.core.port.CheckRepository;
import com.databridge.core.port.DatasetRepository;
import com.databridge.core.port.FindingRepository;
import com.databridge.core.run.Outcome;
import com.databridge.core.run.RunStatus;
import com.databridge.core.schema.SchemaExtractor;
import com.databridge.core.schema.WorkbookSchema;
import com.databridge.core.setup.DataBridgeSetup;
import com.databridge.core.setup.EffectiveSetup;
import com.databridge.core.validation.SheetData;
import com.databridge.core.validation.ValidationResult;
import com.databridge.core.validation.Validator;
import com.databridge.core.validation.WorkbookReader;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Takes in template workbooks for a run and validates them.
 *
 * <p>Workbooks arrive in a <em>submission</em>: each is streamed into the submission's workspace
 * (size-limited, SHA-256 hashed). Processing reads every workbook, records one Dataset Version
 * per workbook (metadata and structure only), validates all of them together, so that references
 * across workbooks are checked, stores the findings with masked values, and lets the workspace
 * be destroyed. A workbook that cannot be read is recorded as ERROR, and the check then ends in
 * ERROR: never in a pass.
 *
 * <p>{@link #intake} is a submission of one workbook, kept for single uploads.
 */
public final class IntakeService {

    private static final int MAX_FILE_NAME = 260;
    private static final int MAX_ERROR = 1000;

    private final MigrationService migrations;
    private final DatasetRepository datasets;
    private final SetupService setups;
    private final RuleService rules;
    private final CheckRepository checks;
    private final FindingRepository findings;
    private final com.databridge.core.workspace.WorkspaceManagers workspaces;
    private final AuditLog audit;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Submission> open = new ConcurrentHashMap<>();

    public IntakeService(MigrationService migrations, DatasetRepository datasets, SetupService setups, RuleService rules,
                         CheckRepository checks, FindingRepository findings,
                         com.databridge.core.workspace.WorkspaceManagers workspaces, AuditLog audit, Clock clock) {
        this.migrations = migrations;
        this.datasets = datasets;
        this.setups = setups;
        this.rules = rules;
        this.checks = checks;
        this.findings = findings;
        this.workspaces = workspaces;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ submissions

    public Submission.View openSubmission(String runId, String actor) {
        String who = AuditLog.actor(actor);
        MigrationRun run = openRun(runId);
        Submission s = new Submission(UUID.randomUUID().toString(), run.id(), who, now(),
                workspaces.forSetup(setups.effective().general()).open());
        open.put(s.id, s);
        return s.view();
    }

    public Submission.View submission(String id) {
        return live(id).view();
    }

    /**
     * Streams a workbook into the submission, replacing any earlier one of the same kind. A file
     * name that is not a workbook is refused; a file that is too large or empty is recorded with
     * its error and reported as an ERROR dataset when the submission is processed.
     */
    public Submission.File addFile(String submissionId, TemplateKind kind, String fileName, InputStream content, String actor) {
        AuditLog.actor(actor);
        if (kind == null) throw new ValidationException("Template kind is required (STATIC, TRANSACTIONAL, HR or OTHER)");
        String name = displayName(fileName);
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".xlsx") && !lower.endsWith(".xlsm")) {
            throw new ValidationException("Only .xlsx or .xlsm workbooks are accepted");
        }
        Submission s = live(submissionId);
        long limit = (long) setups.effective().general().getMaxUploadMb() * 1024 * 1024;
        synchronized (s) {
            Path target = s.workspace.file(kind.name().toLowerCase(Locale.ROOT) + ".xlsx");
            long[] size = {0};
            Submission.File f;
            try {
                f = new Submission.File(kind, name, copyLimited(content, target, limit, size), size[0], null);
            } catch (ValidationException | IOException e) {
                f = new Submission.File(kind, name, null, size[0], e.getMessage());
            }
            s.files.put(kind, f);
            s.paths.put(kind, target);
            return f;
        }
    }

    /** Reads, records and validates every workbook of the submission, then destroys its files. */
    public SubmissionResult process(String submissionId, String actor) {
        String who = AuditLog.actor(actor);
        Submission s = live(submissionId);
        synchronized (s) {
            try {
                if (s.files.isEmpty()) throw new ValidationException("Add at least one workbook before processing");
                MigrationRun run = openRun(s.runId);
                EffectiveSetup setup = setups.effective();
                DataBridgeSetup g = setup.general();
                SchemaExtractor extractor = new SchemaExtractor(setup.templates(), g.getCaptionScanRows());

                List<IntakeResult> recorded = new ArrayList<>();
                List<SheetData> sheets = new ArrayList<>();
                boolean anyError = false;
                for (Submission.File f : s.files.values()) {
                    Path path = s.paths.get(f.kind());
                    Outcome<List<SheetData>> read = f.error() != null
                            ? Outcome.error("intake", new ValidationException(f.error()))
                            : Outcome.run("intake", () -> {
                                WorkbookSchema schema = extractor.extract(path);
                                return WorkbookReader.read(path, new WorkbookSchema(f.fileName(), schema.sheets()), f.kind());
                            });
                    WorkbookSchema schema = read.isSuccess()
                            ? new WorkbookSchema(f.fileName(), read.value().stream().map(SheetData::schema).toList()) : null;
                    if (read.isSuccess()) sheets.addAll(read.value());
                    else anyError = true;
                    recorded.add(new IntakeResult(record(run, f, read, schema, who), schema));
                }

                Outcome<ValidationResult> v = Outcome.run("validate",
                        () -> new Validator(rules.effective(), new ValueMasker(g.getFindingValueMode())).validate(sheets));
                ValidationResult result = v.isSuccess() ? v.value() : new ValidationResult(List.of(), List.of(), List.of());
                ValidationCheck check = saveCheck(run, recorded, result, anyError, v.error().orElse(null), who);
                audit.record(who, "CHECK_COMPLETED", "CHECK", check.id(), "Check " + check.checkNo() + ": " + check.status()
                        + ", " + check.rows() + " row(s), " + check.critical() + " critical, " + check.warning()
                        + " warning(s), readiness " + check.readiness().movePointRight(2).setScale(1, RoundingMode.HALF_UP) + "%");
                return new SubmissionResult(recorded, check, result);
            } finally {
                close(s);
            }
        }
    }

    /** Destroys a submission's files without processing them. */
    public void cancel(String submissionId, String actor) {
        AuditLog.actor(actor);
        Submission s = open.get(submissionId);
        if (s != null) close(s);
    }

    /** Closes submissions whose workspace has expired; returns how many. */
    public int sweepSubmissions() {
        Instant now = clock.instant();
        int n = 0;
        for (Submission s : List.copyOf(open.values())) {
            if (!s.workspace.expiresAt().isAfter(now)) {
                close(s);
                n++;
            }
        }
        return n;
    }

    /** A one-workbook submission: upload, record and validate in a single step. */
    public IntakeResult intake(String runId, TemplateKind kind, String fileName, InputStream content, String actor) {
        if (kind == null) throw new ValidationException("Template kind is required (STATIC, TRANSACTIONAL, HR or OTHER)");
        String name = displayName(fileName);
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".xlsx") && !lower.endsWith(".xlsm")) {
            throw new ValidationException("Only .xlsx or .xlsm workbooks are accepted");
        }
        Submission.View s = openSubmission(runId, actor);
        try {
            addFile(s.id(), kind, name, content, actor);
        } catch (RuntimeException e) {
            cancel(s.id(), actor);
            throw e;
        }
        return process(s.id(), actor).datasets().get(0);
    }

    /** The result of the most recent processing for a single upload, with its check. */
    public SubmissionResult intakeChecked(String runId, TemplateKind kind, String fileName, InputStream content, String actor) {
        Submission.View s = openSubmission(runId, actor);
        try {
            addFile(s.id(), kind, fileName, content, actor);
        } catch (RuntimeException e) {
            cancel(s.id(), actor);
            throw e;
        }
        return process(s.id(), actor);
    }

    public List<DatasetVersion> datasets(String runId) {
        return datasets.listByRun(migrations.run(runId).id());
    }

    public DatasetVersion dataset(String id) {
        return datasets.find(id).orElseThrow(() -> new NotFoundException("Dataset", id));
    }

    // ------------------------------------------------------------------ internals

    private DatasetVersion record(MigrationRun run, Submission.File f, Outcome<?> read, WorkbookSchema schema, String who) {
        int version = datasets.maxVersion(run.id(), f.kind()) + 1;
        DatasetVersion d = new DatasetVersion(
                UUID.randomUUID().toString(), run.id(), f.kind(), version, f.fileName(), f.sha256(), f.sizeBytes(),
                schema == null ? 0 : schema.sheets().size(),
                schema == null ? 0 : schema.sheets().stream().mapToInt(t -> t.warnings().size()).sum(),
                read.status(),
                read.error().map(e -> e.length() > MAX_ERROR ? e.substring(0, MAX_ERROR) : e).orElse(null),
                schema == null ? null : toJson(schema), now(), who);
        datasets.insert(d);
        String summary = f.kind() + " v" + version + ": " + (read.isSuccess()
                ? d.sheetCount() + " sheet(s), " + d.warningCount() + " warning(s), " + d.sizeBytes() + " bytes, sha256 "
                  + shortHash(d.sha256())
                : "ERROR " + d.error());
        audit.record(who, "DATASET_UPLOADED", "DATASET", d.id(), summary);
        return d;
    }

    private ValidationCheck saveCheck(MigrationRun run, List<IntakeResult> recorded, ValidationResult result,
                                      boolean anyError, String error, String who) {
        int critical = (int) result.count(Severity.CRITICAL);
        RunStatus status = anyError || error != null ? RunStatus.ERROR : critical > 0 ? RunStatus.FAILED : RunStatus.SUCCEEDED;
        String id = UUID.randomUUID().toString();
        List<ValidationCheck> earlier = checks.listByRun(run.id());
        ValidationCheck check = new ValidationCheck(id, run.id(), checks.maxCheckNo(run.id()) + 1, status,
                String.join(",", recorded.stream().map(r -> r.dataset().id()).toList()), result.rows(), critical,
                (int) result.count(Severity.WARNING), (int) result.count(Severity.INFO),
                BigDecimal.valueOf(result.readiness()).setScale(4, RoundingMode.HALF_UP),
                toJson(result.sheets()), toJson(result.skipped()),
                error == null && anyError ? "One or more workbooks could not be read" : error, now(), who);
        checks.insert(check);

        Map<String, StoredFinding> waived = new HashMap<>();
        int earlierNo = 0;
        if (!earlier.isEmpty()) {
            ValidationCheck prev = earlier.get(0);
            earlierNo = prev.checkNo();
            for (StoredFinding f : findings.list(prev.id(),
                    new com.databridge.core.domain.FindingFilter(null, FindingStatus.WAIVED, null, null), 0, Integer.MAX_VALUE)) {
                waived.put(f.matchKey(), f);
            }
        }
        List<StoredFinding> rows = new ArrayList<>();
        for (Finding f : result.findings()) {
            StoredFinding sf = StoredFinding.of(UUID.randomUUID().toString(), id, run.id(), f);
            StoredFinding w = waived.get(sf.matchKey());
            rows.add(w == null ? sf : sf.carryWaiverFrom(w, earlierNo));
        }
        findings.insertAll(rows);
        return check;
    }

    private MigrationRun openRun(String runId) {
        MigrationRun run = migrations.run(runId);
        if (run.state() != RunState.OPEN) throw new ValidationException("Run " + run.runNo() + " is closed");
        return run;
    }

    private Submission live(String id) {
        Submission s = open.get(id);
        if (s == null) throw new NotFoundException("Submission (it may have expired or been processed)", id);
        return s;
    }

    private void close(Submission s) {
        open.remove(s.id);
        s.workspace.close();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
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

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot serialise", e);
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
