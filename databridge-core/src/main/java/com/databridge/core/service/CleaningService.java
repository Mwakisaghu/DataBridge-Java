package com.databridge.core.service;

import com.databridge.core.cleaning.CleanChange;
import com.databridge.core.cleaning.CleanLoader;
import com.databridge.core.cleaning.CleanResult;
import com.databridge.core.cleaning.CleanRule;
import com.databridge.core.cleaning.Cleaner;
import com.databridge.core.domain.CleaningJob;
import com.databridge.core.domain.MigrationRun;
import com.databridge.core.domain.TemplateKind;
import com.databridge.core.port.CleaningRepository;
import com.databridge.core.domain.RunState;
import com.databridge.core.run.RunStatus;
import com.databridge.core.schema.SchemaExtractor;
import com.databridge.core.schema.WorkbookSchema;
import com.databridge.core.setup.DataBridgeSetup;
import com.databridge.core.setup.EffectiveSetup;
import com.databridge.core.setup.SetupLoader;
import com.databridge.core.workspace.Workspace;
import com.databridge.core.workspace.WorkspaceManagers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Module 3: the Cleaning Setup and clean-and-download. The workbook is streamed into a short-lived
 * workspace, cleaned in memory, returned to the caller and the workspace destroyed; only the
 * change log (where and what kind of change, never values) and file fingerprints are kept.
 */
public final class CleaningService {

    /** A rule in force and where it comes from. */
    public record RuleView(CleanRule rule, boolean shipped, boolean stored) { }

    /** The cleaned workbook (to send, never to store) and the job that describes it. */
    public record Output(CleaningJob job, List<CleanChange> changes, List<String> skipped, byte[] workbook, String fileName) { }

    /** A job with its change log. */
    public record JobView(CleaningJob job, List<CleanChange> changes) { }

    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9.\\-_]{1,79}$");

    private final CleaningRepository repo;
    private final MigrationService migrations;
    private final SetupService setups;
    private final WorkspaceManagers workspaces;
    private final AuditLog audit;
    private final Clock clock;
    private final List<CleanRule> defaults;

    public CleaningService(CleaningRepository repo, MigrationService migrations, SetupService setups,
                           WorkspaceManagers workspaces, AuditLog audit, Clock clock) {
        this(repo, migrations, setups, workspaces, audit, clock, CleanLoader.defaults());
    }

    public CleaningService(CleaningRepository repo, MigrationService migrations, SetupService setups,
                           WorkspaceManagers workspaces, AuditLog audit, Clock clock, List<CleanRule> defaults) {
        this.repo = repo;
        this.migrations = migrations;
        this.setups = setups;
        this.workspaces = workspaces;
        this.audit = audit;
        this.clock = clock;
        this.defaults = defaults.stream().map(CleaningService::copy).toList();
    }

    // ------------------------------------------------------------------ setup

    public List<RuleView> views() {
        Map<String, RuleView> out = new LinkedHashMap<>();
        for (CleanRule d : defaults) out.put(d.getId(), new RuleView(copy(d), true, false));
        for (CleanRule s : repo.rules()) out.put(s.getId(), new RuleView(s, out.containsKey(s.getId()), true));
        return new ArrayList<>(out.values());
    }

    public List<CleanRule> effective() {
        return views().stream().map(RuleView::rule).toList();
    }

    public CleanRule put(CleanRule r, String actor) {
        String who = AuditLog.actor(actor);
        validate(r);
        boolean existed = views().stream().anyMatch(v -> v.rule().getId().equals(r.getId()));
        repo.saveRule(r, who, clock.instant().truncatedTo(ChronoUnit.MILLIS));
        audit.record(who, existed ? "CLEANING_RULE_CHANGED" : "CLEANING_RULE_ADDED", "CLEANING_RULE", r.getId(),
                r.getAction() + " on " + r.getSheet() + " / " + r.getField() + (r.isEnabled() ? "" : ", switched off"));
        return r;
    }

    public boolean delete(String id, String actor) {
        String who = AuditLog.actor(actor);
        boolean removed = repo.deleteRule(id);
        if (removed) audit.record(who, "CLEANING_RULE_DELETED", "CLEANING_RULE", id, "Shipped default applies again, if any");
        return removed;
    }

    static void validate(CleanRule r) {
        if (r.getId() == null || !ID.matcher(r.getId()).matches()) {
            throw new ValidationException("Rule id must be 2-80 lower-case letters, digits, '.', '-' or '_'");
        }
        if (r.getAction() == null) throw new ValidationException(r.getId() + ": an action is required");
        if (blank(r.getSheet()) || blank(r.getField())) throw new ValidationException(r.getId() + ": sheet and field are required (* for all)");
        switch (r.getAction()) {
            case REPLACE:
                if (blank(r.getPattern())) throw new ValidationException(r.getId() + ": a pattern is required");
                try {
                    Pattern.compile(r.getPattern());
                } catch (PatternSyntaxException e) {
                    throw new ValidationException(r.getId() + ": the pattern is not a valid regular expression");
                }
                break;
            case PAD_LEFT:
                if (r.getLength() == null || r.getLength() < 1 || r.getLength() > 250) {
                    throw new ValidationException(r.getId() + ": a length of 1 to 250 is required");
                }
                break;
            case DEFAULT_VALUE:
                if (blank(r.getValue())) throw new ValidationException(r.getId() + ": the value to fill in is required");
                if (r.getField().equals("*")) throw new ValidationException(r.getId() + ": name the column to fill");
                break;
            case REMOVE_DUPLICATE_ROWS:
                if (!r.getField().equals("*")) throw new ValidationException(r.getId() + ": duplicate rows are compared on every column; use field *");
                if (r.getSheet().equals("*")) throw new ValidationException(r.getId() + ": name the sheet whose duplicate rows are removed");
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ cleaning

    /** Cleans one workbook with the setup in force and returns it; nothing of the file is kept. */
    public Output clean(String runId, TemplateKind kind, String fileName, InputStream content, String actor) {
        String who = AuditLog.actor(actor);
        if (kind == null) throw new ValidationException("Template kind is required (STATIC, TRANSACTIONAL, HR or OTHER)");
        String name = IntakeService.displayName(fileName);
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".xlsx") && !lower.endsWith(".xlsm")) throw new ValidationException("Only .xlsx or .xlsm workbooks are accepted");
        MigrationRun run = migrations.run(runId);
        if (run.state() != RunState.OPEN) throw new ValidationException("Run " + run.runNo() + " is closed");
        EffectiveSetup setup = setups.effective();
        DataBridgeSetup g = setup.general();
        long limit = (long) g.getMaxUploadMb() * 1024 * 1024;

        try (Workspace ws = workspaces.forSetup(g).open()) {
            Path in = ws.file("in.xlsx");
            long[] size = {0};
            String shaIn;
            try {
                shaIn = IntakeService.copyLimited(content, in, limit, size);
            } catch (IOException e) {
                throw new ValidationException("The upload was interrupted: " + e.getMessage());
            }
            CleanResult result;
            byte[] out;
            try {
                WorkbookSchema schema = new SchemaExtractor(setup.templates(), g.getCaptionScanRows()).extract(in);
                try (InputStream fin = Files.newInputStream(in); Workbook wb = WorkbookFactory.create(fin)) {
                    result = new Cleaner(effective()).clean(wb, schema);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(size[0] * 2, Integer.MAX_VALUE - 8));
                    wb.write(bos);
                    out = bos.toByteArray();
                }
            } catch (IOException | RuntimeException e) {
                CleaningJob failed = new CleaningJob(UUID.randomUUID().toString(), run.id(), kind, name, shaIn, size[0], null, 0, 0,
                        RunStatus.ERROR, cut("The workbook could not be cleaned: " + e.getMessage(), 1000), now(), who);
                repo.insertJob(failed, List.of());
                audit.record(who, "WORKBOOK_CLEANED", "CLEANING_JOB", failed.id(), kind + " " + name + ": ERROR");
                throw new ValidationException(failed.error());
            }
            CleaningJob job = new CleaningJob(UUID.randomUUID().toString(), run.id(), kind, name, shaIn, size[0], sha256(out),
                    result.cells(), result.rowsEmptied(), RunStatus.SUCCEEDED, null, now(), who);
            repo.insertJob(job, result.changes());
            audit.record(who, "WORKBOOK_CLEANED", "CLEANING_JOB", job.id(), kind + " " + name + ": " + job.cells()
                    + " cell(s) changed, " + job.rowsEmptied() + " duplicate row(s) emptied; cleaned file returned, not kept");
            return new Output(job, result.changes(), result.skipped(), out, cleanedName(name));
        }
    }

    public List<CleaningJob> jobs(String runId) {
        return repo.jobs(migrations.run(runId).id());
    }

    public JobView job(String id) {
        CleaningJob j = repo.job(id).orElseThrow(() -> new NotFoundException("Cleaning job", id));
        return new JobView(j, repo.changes(j.id()));
    }

    static String cleanedName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name + " (cleaned)" : name.substring(0, dot) + " (cleaned)" + name.substring(dot);
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private java.time.Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static CleanRule copy(CleanRule r) {
        ObjectMapper m = SetupLoader.mapper();
        return m.convertValue(m.valueToTree(r), CleanRule.class);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
