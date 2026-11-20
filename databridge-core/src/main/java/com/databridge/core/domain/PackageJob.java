package com.databridge.core.domain;

import java.time.Instant;
import java.util.List;

/**
 * One import package built from a validated workbook: its fingerprints and its tables in import
 * order. The package file itself is never kept.
 */
public record PackageJob(String id, String runId, String checkId, TemplateKind kind, String fileName, String sha256In,
                         String sha256Out, List<PackageTable> tables, Instant createdAt, String createdBy) {

    public PackageJob {
        tables = tables == null ? List.of() : List.copyOf(tables);
    }

    /** One table of the package, in the order it must be imported. */
    public record PackageTable(int order, String sheet, String tableName, Integer tableId, int rows) { }
}
