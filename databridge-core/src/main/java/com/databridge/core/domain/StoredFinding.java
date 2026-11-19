package com.databridge.core.domain;

import com.databridge.core.finding.Finding;
import com.databridge.core.finding.FindingStatus;
import com.databridge.core.finding.Severity;

import java.time.Instant;

/** A finding as kept in the exception register, with its waiver trail (maker-checker). */
public record StoredFinding(
        String id, String checkId, String runId, String kind, String sheet, int rowNo, String columnRef, String field,
        String ruleId, String ruleType, Severity severity, String message, String value, boolean autoFixable,
        FindingStatus status, String waiverReason, String requestedBy, Instant requestedAt,
        String decidedBy, Instant decidedAt, String decisionNote) {

    public static StoredFinding of(String id, String checkId, String runId, Finding f) {
        return new StoredFinding(id, checkId, runId, f.kind(), f.sheet(), f.row(), f.column(), f.field(), f.ruleId(),
                f.ruleType(), f.severity(), f.message(), f.value(), f.autoFixable(), f.status(),
                null, null, null, null, null, null);
    }

    /** Same problem in a later check: same workbook, sheet, row, rule and (masked) value. */
    public String matchKey() {
        return kind + "|" + sheet + "|" + rowNo + "|" + ruleId + "|" + value;
    }

    public StoredFinding requested(String reason, String by, Instant at) {
        return new StoredFinding(id, checkId, runId, kind, sheet, rowNo, columnRef, field, ruleId, ruleType, severity,
                message, value, autoFixable, FindingStatus.WAIVER_REQUESTED, reason, by, at, null, null, null);
    }

    public StoredFinding decided(FindingStatus to, String by, Instant at, String note) {
        return new StoredFinding(id, checkId, runId, kind, sheet, rowNo, columnRef, field, ruleId, ruleType, severity,
                message, value, autoFixable, to, waiverReason, requestedBy, requestedAt, by, at, note);
    }

    /** A copy for a new check that keeps the waiver of an identical earlier finding. */
    public StoredFinding carryWaiverFrom(StoredFinding earlier, int earlierCheckNo) {
        return new StoredFinding(id, checkId, runId, kind, sheet, rowNo, columnRef, field, ruleId, ruleType, severity,
                message, value, autoFixable, FindingStatus.WAIVED, earlier.waiverReason(), earlier.requestedBy(),
                earlier.requestedAt(), earlier.decidedBy(), earlier.decidedAt(),
                "Waiver carried forward from check " + earlierCheckNo);
    }
}
