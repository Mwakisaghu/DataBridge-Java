package com.databridge.core.recon;

import com.databridge.core.finding.Finding;
import com.databridge.core.validation.SkippedRule;

import java.util.List;

/** The reconciliation lines and the findings raised for the ones that do not tie. */
public record ReconResult(List<ReconLine> lines, List<Finding> findings, List<SkippedRule> skipped) {

    public ReconResult {
        lines = List.copyOf(lines);
        findings = List.copyOf(findings);
        skipped = List.copyOf(skipped);
    }

    public static ReconResult none() {
        return new ReconResult(List.of(), List.of(), List.of());
    }
}
