package com.databridge.core.service;

import com.databridge.core.domain.Client;
import com.databridge.core.domain.CleaningJob;
import com.databridge.core.domain.FindingFilter;
import com.databridge.core.domain.ImportRecord;
import com.databridge.core.domain.MigrationRun;
import com.databridge.core.domain.PackageJob;
import com.databridge.core.domain.Project;
import com.databridge.core.domain.RunState;
import com.databridge.core.domain.SignOff;
import com.databridge.core.domain.ValidationCheck;
import com.databridge.core.finding.FindingStatus;
import com.databridge.core.finding.Severity;
import com.databridge.core.port.CheckRepository;
import com.databridge.core.port.FindingRepository;
import com.databridge.core.port.SignOffRepository;
import com.databridge.core.recon.ReconLine;
import com.databridge.core.report.SignOffPack;
import com.databridge.core.run.RunStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Module 6: the state of a run in one view (KPIs, readiness trend, readiness checklist), and the
 * maker-checker sign-off that closes it with a certificate.
 */
public final class ReportingService {

    public record TrendPoint(int checkNo, Instant at, RunStatus status, BigDecimal readiness, int critical, int warning) { }
    public record FindingKpis(int openCritical, int awaitingCritical, int waivedCritical, int openWarning, int waivedTotal) { }
    public record ReconKpis(int lines, int ties, int variances, int notChecked, int unapproved) { }
    public record CleaningKpis(int jobs, int cells, int rowsEmptied) { }
    public record ImportKpis(int tables, int done, int pending, int problems) { }
    /** One readiness item: what it is, whether it passes and why. */
    public record CheckItem(String id, String label, boolean passed, String detail) { }

    public record RunSummary(Client client, Project project, MigrationRun run, ValidationCheck latestCheck, List<TrendPoint> trend,
                             FindingKpis findings, ReconKpis reconciliation, CleaningKpis cleaning, int packages,
                             ImportKpis imports, List<CheckItem> checklist, boolean ready, SignOff signOff) { }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final MigrationService migrations;
    private final CheckRepository checks;
    private final FindingRepository findings;
    private final ReconciliationService recon;
    private final CleaningService cleaning;
    private final IntegrationService integration;
    private final SignOffRepository signOffs;
    private final AuditLog audit;
    private final Clock clock;

    public ReportingService(MigrationService migrations, CheckRepository checks, FindingRepository findings,
                            ReconciliationService recon, CleaningService cleaning, IntegrationService integration,
                            SignOffRepository signOffs, AuditLog audit, Clock clock) {
        this.migrations = migrations;
        this.checks = checks;
        this.findings = findings;
        this.recon = recon;
        this.cleaning = cleaning;
        this.integration = integration;
        this.signOffs = signOffs;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ summary

    public RunSummary summary(String runId) {
        MigrationRun run = migrations.run(runId);
        Project p = migrations.project(run.projectId());
        Client c = migrations.client(p.clientId());
        List<ValidationCheck> cs = checks.listByRun(run.id());
        ValidationCheck latest = cs.isEmpty() ? null : cs.get(0);

        List<TrendPoint> trend = new ArrayList<>();
        for (int i = cs.size() - 1; i >= 0; i--) {
            ValidationCheck k = cs.get(i);
            trend.add(new TrendPoint(k.checkNo(), k.createdAt(), k.status(), k.readiness(), k.critical(), k.warning()));
        }

        FindingKpis fk = new FindingKpis(0, 0, 0, 0, 0);
        ReconKpis rk = new ReconKpis(0, 0, 0, 0, 0);
        if (latest != null) {
            fk = new FindingKpis(count(latest, Severity.CRITICAL, FindingStatus.OPEN), count(latest, Severity.CRITICAL, FindingStatus.WAIVER_REQUESTED),
                    count(latest, Severity.CRITICAL, FindingStatus.WAIVED), count(latest, Severity.WARNING, FindingStatus.OPEN),
                    count(latest, null, FindingStatus.WAIVED));
            List<ReconciliationService.Line> lines = recon.lines(latest.id());
            int ties = 0, vars = 0, nc = 0, unapproved = 0;
            for (ReconciliationService.Line l : lines) {
                switch (l.line().status()) {
                    case TIES: ties++; break;
                    case VARIANCE: vars++; break;
                    default: nc++;
                }
                if (l.line().status() == ReconLine.Status.VARIANCE && (l.finding() == null || l.finding().status() != FindingStatus.WAIVED)) {
                    unapproved++;
                }
            }
            rk = new ReconKpis(lines.size(), ties, vars, nc, unapproved);
        }
        List<CleaningJob> jobs = cleaning.jobs(run.id());
        CleaningKpis ck = new CleaningKpis(jobs.size(), jobs.stream().mapToInt(CleaningJob::cells).sum(),
                jobs.stream().mapToInt(CleaningJob::rowsEmptied).sum());
        List<PackageJob> pkgs = integration.packages(run.id());
        List<ImportRecord> imps = integration.imports(run.id());
        int done = (int) imps.stream().filter(r -> r.status() == ImportRecord.Status.IMPORTED || r.status() == ImportRecord.Status.MATCHED).count();
        int pending = (int) imps.stream().filter(r -> r.status() == ImportRecord.Status.PENDING).count();
        ImportKpis ik = new ImportKpis(imps.size(), done, pending, imps.size() - done - pending);

        List<CheckItem> items = checklist(latest, fk, rk, pkgs, imps);
        boolean ready = items.stream().allMatch(CheckItem::passed);
        return new RunSummary(c, p, run, latest, trend, fk, rk, ck, pkgs.size(), ik, items, ready,
                signOffs.find(run.id()).orElse(null));
    }

    private int count(ValidationCheck k, Severity sev, FindingStatus st) {
        return findings.count(k.id(), new FindingFilter(sev, st, null, null));
    }

    static List<CheckItem> checklist(ValidationCheck latest, FindingKpis fk, ReconKpis rk, List<PackageJob> pkgs, List<ImportRecord> imps) {
        List<CheckItem> out = new ArrayList<>();
        if (latest == null) {
            out.add(new CheckItem("validated", "Workbooks validated", false, "No check has been run"));
        } else if (latest.status() == RunStatus.ERROR) {
            out.add(new CheckItem("validated", "Workbooks validated", false, "Check " + latest.checkNo() + " could not run to the end"));
        } else {
            int open = fk.openCritical() + fk.awaitingCritical();
            out.add(new CheckItem("validated", "Workbooks validated", open == 0, open == 0
                    ? "Check " + latest.checkNo() + ": no open Critical finding (" + fk.waivedCritical() + " waived)"
                    : "Check " + latest.checkNo() + ": " + fk.openCritical() + " open and " + fk.awaitingCritical() + " awaiting a waiver decision"));
        }
        if (rk.lines() == 0 || rk.lines() == rk.notChecked()) {
            out.add(new CheckItem("reconciled", "Opening balances reconciled", false, "Nothing was reconciled: submit the transactional workbook"));
        } else if (rk.notChecked() > 0) {
            out.add(new CheckItem("reconciled", "Opening balances reconciled", false, rk.notChecked() + " line(s) could not be checked"));
        } else {
            out.add(new CheckItem("reconciled", "Opening balances reconciled", rk.unapproved() == 0, rk.unapproved() == 0
                    ? rk.ties() + " tie, " + rk.variances() + " variance(s) explained and approved by Finance"
                    : rk.unapproved() + " variance(s) without an approved explanation"));
        }
        boolean fromLatest = latest != null && pkgs.stream().anyMatch(p -> latest.id().equals(p.checkId()));
        out.add(new CheckItem("packaged", "Import package built from the latest check", fromLatest, fromLatest
                ? pkgs.stream().filter(p -> latest.id().equals(p.checkId())).count() + " package(s) from check " + latest.checkNo()
                : pkgs.isEmpty() ? "No package built" : "Packages were built from an earlier check or without a check"));
        long notDone = imps.stream().filter(r -> r.status() != ImportRecord.Status.IMPORTED && r.status() != ImportRecord.Status.MATCHED).count();
        out.add(new CheckItem("imported", "Every packaged table imported", !imps.isEmpty() && notDone == 0, imps.isEmpty()
                ? "Nothing to import yet" : notDone == 0 ? imps.size() + " table(s) imported" + matched(imps)
                : notDone + " of " + imps.size() + " table(s) not imported, partly imported, failed or differing from Business Central"));
        return out;
    }

    private static String matched(List<ImportRecord> imps) {
        long m = imps.stream().filter(r -> r.status() == ImportRecord.Status.MATCHED).count();
        return m == 0 ? "" : ", " + m + " confirmed by count in Business Central";
    }

    // ------------------------------------------------------------------ sign-off

    /** The consultant asks for sign-off; every readiness item must pass. */
    public SignOff requestSignOff(String runId, String note, String actor) {
        String who = AuditLog.actor(actor);
        RunSummary s = summary(runId);
        if (s.run().state() != RunState.OPEN) throw new ValidationException("Run " + s.run().runNo() + " is closed");
        if (s.signOff() != null && s.signOff().status() == SignOff.Status.REQUESTED) throw new ValidationException("Sign-off is already requested");
        if (!s.ready()) {
            throw new ValidationException("The run is not ready for sign-off: " + String.join("; ",
                    s.checklist().stream().filter(i -> !i.passed()).map(i -> i.label() + " (" + i.detail() + ")").toList()));
        }
        if (note != null && note.length() > 500) throw new ValidationException("The note is longer than 500 characters");
        SignOff so = new SignOff(s.run().id(), SignOff.Status.REQUESTED, null, toJson(s.checklist()), who, now(),
                blank(note) ? null : note.trim(), null, null, null);
        signOffs.save(so);
        audit.record(who, "SIGNOFF_REQUESTED", "RUN", s.run().id(), "Run " + s.run().runNo() + " of " + s.client().code() + "/"
                + s.project().code() + ": all " + s.checklist().size() + " readiness items pass");
        return so;
    }

    /** Finance decides; approval issues the certificate number and closes the run. */
    public SignOff decideSignOff(String runId, boolean approve, String note, String actor) {
        String who = AuditLog.actor(actor);
        MigrationRun run = migrations.run(runId);
        SignOff cur = signOffs.find(run.id()).filter(x -> x.status() == SignOff.Status.REQUESTED)
                .orElseThrow(() -> new ValidationException("No sign-off is waiting on this run"));
        if (who.equalsIgnoreCase(cur.requestedBy())) throw new ValidationException("Maker-checker: the person who requested sign-off cannot also decide it");
        if (!approve && blank(note)) throw new ValidationException("Say why the sign-off is rejected");
        if (approve) {
            RunSummary s = summary(runId); // the data must still be ready now, not only when requested
            if (!s.ready()) throw new ValidationException("The run is no longer ready: something changed after sign-off was requested");
        }
        Project p = migrations.project(run.projectId());
        Client c = migrations.client(p.clientId());
        Instant at = now();
        String cert = approve ? "DB-" + c.code() + "-" + p.code() + "-R" + run.runNo() + "-" + DAY.format(at) : null;
        SignOff u = new SignOff(cur.runId(), approve ? SignOff.Status.APPROVED : SignOff.Status.REJECTED, cert, cur.checklistJson(),
                cur.requestedBy(), cur.requestedAt(), cur.requestNote(), who, at, blank(note) ? null : note.trim());
        signOffs.save(u);
        audit.record(who, approve ? "SIGNOFF_APPROVED" : "SIGNOFF_REJECTED", "RUN", run.id(),
                approve ? "Certificate " + cert : "Rejected: " + note.trim());
        if (approve) migrations.closeRun(run.id(), who);
        return u;
    }

    /** The sign-off pack: run, KPIs, checklist, approvals and the audit trail of the run, as .xlsx. */
    public byte[] signOffPack(String runId) {
        RunSummary s = summary(runId);
        List<CheckItem> snapshot = s.signOff() == null ? s.checklist() : fromJson(s.signOff().checklistJson());
        return SignOffPack.write(s, snapshot, audit.list("RUN", s.run().id(), 500));
    }

    /** The audit trail as .xlsx. */
    public byte[] auditExport(String entityType, String entityId, int limit) {
        return SignOffPack.audit(audit.list(entityType, entityId, Math.max(1, Math.min(limit, 10_000))));
    }

    public Optional<SignOff> signOff(String runId) {
        return signOffs.find(migrations.run(runId).id());
    }

    // ------------------------------------------------------------------ helpers

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<CheckItem> fromJson(String json) {
        try {
            return JSON.readValue(json, new TypeReference<List<CheckItem>>() { });
        } catch (IOException e) {
            throw new IllegalStateException("Stored sign-off checklist cannot be read", e);
        }
    }
}
