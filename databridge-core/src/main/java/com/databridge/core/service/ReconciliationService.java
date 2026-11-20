package com.databridge.core.service;

import com.databridge.core.domain.Client;
import com.databridge.core.domain.FindingFilter;
import com.databridge.core.domain.MigrationRun;
import com.databridge.core.domain.Project;
import com.databridge.core.domain.StoredFinding;
import com.databridge.core.domain.ValidationCheck;
import com.databridge.core.port.CheckRepository;
import com.databridge.core.port.FindingRepository;
import com.databridge.core.port.ReconRepository;
import com.databridge.core.recon.ControlDef;
import com.databridge.core.recon.ReconLine;
import com.databridge.core.recon.ReconLoader;
import com.databridge.core.recon.ReconResult;
import com.databridge.core.recon.ReconSetup;
import com.databridge.core.recon.Reconciler;
import com.databridge.core.recon.SourceDef;
import com.databridge.core.recon.TrialBalanceDef;
import com.databridge.core.report.ReconciliationPack;
import com.databridge.core.setup.SetupLoader;
import com.databridge.core.validation.SheetData;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The Reconciliation Setup (shipped defaults plus stored overrides by id), reconciling a
 * submission's sheets, and each check's reconciliation lines and pack.
 */
public final class ReconciliationService {

    /** A control account in force, and whether it comes from the shipped defaults, is stored, or both. */
    public record ControlView(ControlDef control, boolean shipped, boolean stored) { }

    /** The setup in force, with where each part comes from. */
    public record SetupView(TrialBalanceDef trialBalance, boolean trialBalanceStored, List<ControlView> controls) { }

    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9.\\-_]{1,59}$");

    private final ReconRepository repo;
    private final CheckRepository checks;
    private final FindingRepository findings;
    private final MigrationService migrations;
    private final AuditLog audit;
    private final Clock clock;
    private final ReconSetup defaults;

    public ReconciliationService(ReconRepository repo, CheckRepository checks, FindingRepository findings,
                                 MigrationService migrations, AuditLog audit, Clock clock) {
        this(repo, checks, findings, migrations, audit, clock, ReconLoader.defaults());
    }

    public ReconciliationService(ReconRepository repo, CheckRepository checks, FindingRepository findings,
                                 MigrationService migrations, AuditLog audit, Clock clock, ReconSetup defaults) {
        this.repo = repo;
        this.checks = checks;
        this.findings = findings;
        this.migrations = migrations;
        this.audit = audit;
        this.clock = clock;
        this.defaults = copy(defaults, ReconSetup.class);
    }

    // ------------------------------------------------------------------ setup

    public SetupView views() {
        Map<String, ControlView> out = new LinkedHashMap<>();
        for (ControlDef d : defaults.getControls()) out.put(d.getId(), new ControlView(copy(d, ControlDef.class), true, false));
        for (ControlDef s : repo.controls()) out.put(s.getId(), new ControlView(s, out.containsKey(s.getId()), true));
        var stored = repo.trialBalance();
        return new SetupView(stored.orElseGet(() -> copy(defaults.getTrialBalance(), TrialBalanceDef.class)),
                stored.isPresent(), new ArrayList<>(out.values()));
    }

    /** The setup in force, as the reconciler uses it. */
    public ReconSetup effective() {
        SetupView v = views();
        ReconSetup s = new ReconSetup();
        s.setTrialBalance(v.trialBalance());
        s.setControls(new ArrayList<>(v.controls().stream().map(ControlView::control).toList()));
        return s;
    }

    public ControlDef putControl(ControlDef c, String actor) {
        String who = AuditLog.actor(actor);
        validate(c);
        boolean existed = views().controls().stream().anyMatch(v -> v.control().getId().equals(c.getId()));
        repo.saveControl(c, who, clock.instant().truncatedTo(ChronoUnit.MILLIS));
        audit.record(who, existed ? "CONTROL_CHANGED" : "CONTROL_ADDED", "CONTROL", c.getId(),
                "GL " + c.getGlAccount() + " (" + c.getNormal() + ") against " + c.getSources().size() + " subledger(s)"
                        + (c.isEnabled() ? "" : ", switched off"));
        return c;
    }

    public boolean deleteControl(String id, String actor) {
        String who = AuditLog.actor(actor);
        boolean removed = repo.deleteControl(id);
        if (removed) audit.record(who, "CONTROL_DELETED", "CONTROL", id, "Shipped default applies again, if any");
        return removed;
    }

    public TrialBalanceDef putTrialBalance(TrialBalanceDef tb, String actor) {
        String who = AuditLog.actor(actor);
        if (tb == null || blank(tb.getSheet()) || blank(tb.getAccountField()) || blank(tb.getDebitField()) || blank(tb.getCreditField())) {
            throw new ValidationException("The trial balance needs its sheet and the account, debit and credit column captions");
        }
        if (tb.getTolerance().signum() < 0) throw new ValidationException("Tolerance cannot be negative");
        repo.saveTrialBalance(tb, who, clock.instant().truncatedTo(ChronoUnit.MILLIS));
        audit.record(who, "TRIAL_BALANCE_SETUP_CHANGED", "CONTROL", ReconRepository.TRIAL_BALANCE_ID,
                "Sheet " + tb.getSheet() + ", tolerance " + tb.getTolerance());
        return tb;
    }

    public boolean resetTrialBalance(String actor) {
        String who = AuditLog.actor(actor);
        boolean removed = repo.deleteTrialBalance();
        if (removed) audit.record(who, "TRIAL_BALANCE_SETUP_RESET", "CONTROL", ReconRepository.TRIAL_BALANCE_ID, "Shipped default applies again");
        return removed;
    }

    static void validate(ControlDef c) {
        if (c.getId() == null || !ID.matcher(c.getId()).matches()) {
            throw new ValidationException("Control id must be 2-60 lower-case letters, digits, '.', '-' or '_'");
        }
        if (blank(c.getGlAccount())) throw new ValidationException(c.getId() + ": the GL account is required");
        if (c.getNormal() == null) throw new ValidationException(c.getId() + ": say whether the account is normally DEBIT or CREDIT");
        if (c.getSources().isEmpty()) throw new ValidationException(c.getId() + ": add at least one subledger");
        for (SourceDef s : c.getSources()) {
            if (blank(s.getSheet()) || blank(s.getAmountField())) {
                throw new ValidationException(c.getId() + ": every subledger needs a sheet and an amount column");
            }
            if (!blank(s.getWhereField()) && blank(s.getWhereEquals()) && blank(s.getWhereEndsWith())) {
                throw new ValidationException(c.getId() + ": a filter column needs a value to equal or end with");
            }
        }
        if (c.getTolerance().signum() < 0) throw new ValidationException(c.getId() + ": tolerance cannot be negative");
    }

    // ------------------------------------------------------------------ running

    /** Reconciles the sheets of one submission, with the setup in force now. */
    public ReconResult reconcile(List<SheetData> sheets) {
        return new Reconciler(effective()).reconcile(sheets);
    }

    void save(String checkId, String runId, List<ReconLine> lines) {
        repo.insertLines(checkId, runId, lines);
    }

    /** A check's reconciliation lines, with the explanation and approval of each variance. */
    public List<Line> lines(String checkId) {
        ValidationCheck c = check(checkId);
        Map<String, StoredFinding> byRule = new LinkedHashMap<>();
        for (StoredFinding f : findings.list(c.id(), FindingFilter.all(), 0, Integer.MAX_VALUE)) {
            if (f.ruleType().equals("CONTROL") || f.ruleType().equals("TB_BALANCE")) byRule.putIfAbsent(f.ruleId(), f);
        }
        List<Line> out = new ArrayList<>();
        for (ReconLine l : repo.lines(c.id())) {
            String rule = l.type() == ReconLine.Type.TB ? Reconciler.TB_RULE : Reconciler.CONTROL_RULE + l.controlId();
            out.add(new Line(l, byRule.get(rule)));
        }
        return out;
    }

    /** A reconciliation line and, when it does not tie, its finding (explanation, approval). */
    public record Line(ReconLine line, StoredFinding finding) {
        public BigDecimal variance() {
            return line.variance();
        }
    }

    /** The reconciliation pack of a check as an Excel workbook. */
    public byte[] pack(String checkId) {
        ValidationCheck c = check(checkId);
        MigrationRun run = migrations.run(c.runId());
        Project p = migrations.project(run.projectId());
        Client cl = migrations.client(p.clientId());
        return ReconciliationPack.write(cl, p, run, c, lines(checkId));
    }

    private ValidationCheck check(String id) {
        return checks.find(id).orElseThrow(() -> new NotFoundException("Check", id));
    }

    private static <T> T copy(Object o, Class<T> type) {
        ObjectMapper m = SetupLoader.mapper();
        return m.convertValue(m.valueToTree(o), type);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
