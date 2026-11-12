package com.databridge.core.domain;

import com.databridge.core.run.RunStatus;

import java.time.Instant;

/**
 * The record of one uploaded template workbook. Holds metadata and the schema
 * (structure) only; the file itself is destroyed after processing. The SHA-256
 * proves later which exact file was processed without keeping it.
 */
public record DatasetVersion(
        String id,
        String runId,
        TemplateKind kind,
        int versionNo,
        String fileName,
        String sha256,
        long sizeBytes,
        int sheetCount,
        int warningCount,
        RunStatus status,
        String error,
        String schemaJson,
        Instant uploadedAt,
        String uploadedBy) { }
