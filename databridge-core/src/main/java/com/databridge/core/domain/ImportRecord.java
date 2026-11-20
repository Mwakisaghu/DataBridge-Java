package com.databridge.core.domain;

import java.time.Instant;

/**
 * Where the import of one table into Business Central stands for a run: rows in the package, rows
 * the consultant saw imported, and the count read back from Business Central.
 */
public record ImportRecord(String runId, String sheet, Integer tableId, int expectedRows, Integer importedRows, Long bcCount,
                           Status status, String note, Instant updatedAt, String updatedBy) {

    /**
     * PENDING: packaged, not yet imported. IMPORTED / PARTIAL / FAILED: as recorded by the
     * consultant. MATCHED / MISMATCH: after reading the count back from Business Central.
     */
    public enum Status { PENDING, IMPORTED, PARTIAL, FAILED, MATCHED, MISMATCH }
}
