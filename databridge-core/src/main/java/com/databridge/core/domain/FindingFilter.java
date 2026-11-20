package com.databridge.core.domain;

import com.databridge.core.finding.FindingStatus;
import com.databridge.core.finding.Severity;

/** Filters for listing findings; null fields match anything. */
public record FindingFilter(Severity severity, FindingStatus status, String sheet, String ruleType) {
    public static FindingFilter all() {
        return new FindingFilter(null, null, null, null);
    }

    public boolean matches(StoredFinding f) {
        return (severity == null || severity == f.severity()) && (status == null || status == f.status())
                && (sheet == null || sheet.equals(f.sheet())) && (ruleType == null || ruleType.equals(f.ruleType()));
    }
}
