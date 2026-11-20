package com.databridge.core.validation;

import com.databridge.core.finding.Finding;
import com.databridge.core.finding.Severity;

import java.util.List;

/** Everything one validation pass found, plus per-sheet quality and the rules it could not run. */
public record ValidationResult(List<Finding> findings, List<SheetStat> sheets, List<SkippedRule> skipped) {

    public ValidationResult {
        findings = List.copyOf(findings);
        sheets = List.copyOf(sheets);
        skipped = List.copyOf(skipped);
    }

    public long count(Severity s) {
        return findings.stream().filter(f -> f.severity() == s).count();
    }

    public int rows() {
        return sheets.stream().mapToInt(SheetStat::rows).sum();
    }

    /** Rows without a Critical finding over all rows: the readiness of the submission. */
    public double readiness() {
        int rows = rows();
        if (rows == 0) return 1.0;
        return (double) (rows - sheets.stream().mapToInt(SheetStat::rowsWithCritical).sum()) / rows;
    }
}
