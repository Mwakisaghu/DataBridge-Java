package com.databridge.core.domain;

import com.databridge.core.run.RunStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One validation of a run's submitted workbooks. Holds counts, readiness and per-sheet quality
 * (as JSON of counts) only; findings are stored separately with masked values.
 *
 * @param status    SUCCEEDED (no Critical findings), FAILED (Critical findings) or ERROR (a workbook
 *                  could not be read or the check could not run: never treated as a pass)
 * @param readiness rows without a Critical finding over all rows, 0 to 1
 */
public record ValidationCheck(
        String id, String runId, int checkNo, RunStatus status, String datasetIds, int rows,
        int critical, int warning, int info, BigDecimal readiness, String sheetsJson, String skippedJson,
        String error, Instant createdAt, String createdBy) { }
