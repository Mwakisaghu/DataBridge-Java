package com.databridge.core.domain;

import com.databridge.core.run.RunStatus;

import java.time.Instant;

/**
 * One clean-and-download of a workbook: fingerprints of the file before and after, how much was
 * changed, and who did it. The cleaned file itself is never kept.
 */
public record CleaningJob(
        String id, String runId, TemplateKind kind, String fileName, String sha256In, long sizeIn, String sha256Out,
        int cells, int rowsEmptied, RunStatus status, String error, Instant createdAt, String createdBy) { }
