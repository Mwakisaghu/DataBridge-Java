package com.databridge.core.service;

import com.databridge.core.domain.Client;
import com.databridge.core.domain.FindingCount;
import com.databridge.core.domain.FindingFilter;
import com.databridge.core.domain.MigrationRun;
import com.databridge.core.domain.Project;
import com.databridge.core.domain.StoredFinding;
import com.databridge.core.domain.ValidationCheck;
import com.databridge.core.finding.FindingStatus;
import com.databridge.core.port.CheckRepository;
import com.databridge.core.port.FindingRepository;
import com.databridge.core.report.ExceptionRegister;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Validation checks, their findings, and maker-checker waivers. */
public final class FindingService {

    /** A page of findings and how many match in total. */
    public record Page(int total, int offset, List<StoredFinding> items) { }

    public static final int MIN_REASON = 10;

    private final CheckRepository checks;
    private final FindingRepository findings;
    private final MigrationService migrations;
    private final AuditLog audit;
    private final Clock clock;

    public FindingService(CheckRepository checks, FindingRepository findings, MigrationService migrations, AuditLog audit,
                          Clock clock) {
        this.checks = checks;
        this.findings = findings;
        this.migrations = migrations;
        this.audit = audit;
        this.clock = clock;
    }

    public List<ValidationCheck> checks(String runId) {
        return checks.listByRun(migrations.run(runId).id());
    }

    public ValidationCheck check(String id) {
        return checks.find(id).orElseThrow(() -> new NotFoundException("Check", id));
    }

    public List<FindingCount> counts(String checkId) {
        return findings.counts(check(checkId).id());
    }

    public Page page(String checkId, FindingFilter filter, int offset, int limit) {
        String id = check(checkId).id();
        int lim = Math.max(1, Math.min(limit, 500));
        int off = Math.max(0, offset);
        return new Page(findings.count(id, filter), off, findings.list(id, filter, off, lim));
    }

    public StoredFinding finding(String id) {
        return findings.find(id).orElseThrow(() -> new NotFoundException("Finding", id));
    }

    /** The maker asks to accept a finding as it is, with a reason. */
    public StoredFinding requestWaiver(String findingId, String reason, String actor) {
        String who = AuditLog.actor(actor);
        StoredFinding f = finding(findingId);
        if (f.status() != FindingStatus.OPEN) throw new ValidationException("Only an open finding can be waived; this one is " + f.status());
        if (reason == null || reason.trim().length() < MIN_REASON) {
            throw new ValidationException("Give a reason of at least " + MIN_REASON + " characters for the waiver");
        }
        if (reason.length() > 500) throw new ValidationException("The reason is longer than 500 characters");
        StoredFinding u = f.requested(reason.trim(), who, clock.instant().truncatedTo(ChronoUnit.MILLIS));
        findings.update(u);
        audit.record(who, "WAIVER_REQUESTED", "FINDING", f.id(), describe(f) + ": " + reason.trim());
        return u;
    }

    /** The checker approves or rejects the waiver. The checker must not be the maker. */
    public StoredFinding decideWaiver(String findingId, boolean approve, String note, String actor) {
        String who = AuditLog.actor(actor);
        StoredFinding f = finding(findingId);
        if (f.status() != FindingStatus.WAIVER_REQUESTED) throw new ValidationException("No waiver is waiting on this finding");
        if (who.equalsIgnoreCase(f.requestedBy())) {
            throw new ValidationException("Maker-checker: the person who requested a waiver cannot also decide it");
        }
        if (!approve && (note == null || note.isBlank())) throw new ValidationException("Say why the waiver is rejected");
        StoredFinding u = f.decided(approve ? FindingStatus.WAIVED : FindingStatus.OPEN, who,
                clock.instant().truncatedTo(ChronoUnit.MILLIS), note == null ? null : note.trim());
        findings.update(u);
        audit.record(who, approve ? "WAIVER_APPROVED" : "WAIVER_REJECTED", "FINDING", f.id(),
                describe(f) + (note == null || note.isBlank() ? "" : ": " + note.trim()));
        return u;
    }

    /** The exception register of a check as an Excel workbook: summary, per-sheet quality, every finding. */
    public byte[] register(String checkId) {
        ValidationCheck c = check(checkId);
        MigrationRun run = migrations.run(c.runId());
        Project p = migrations.project(run.projectId());
        Client cl = migrations.client(p.clientId());
        List<StoredFinding> all = findings.list(c.id(), FindingFilter.all(), 0, Integer.MAX_VALUE);
        return ExceptionRegister.write(cl, p, run, c, findings.counts(c.id()), all);
    }

    private static String describe(StoredFinding f) {
        return f.severity() + " " + f.ruleType() + " on " + f.sheet() + " row " + f.rowNo() + " (" + f.field() + ")";
    }
}
