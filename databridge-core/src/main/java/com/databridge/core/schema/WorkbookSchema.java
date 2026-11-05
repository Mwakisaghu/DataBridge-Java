package com.databridge.core.schema;

import java.util.List;

/** Schemas for every sheet of one workbook. */
public record WorkbookSchema(String workbook, List<TableSchema> sheets) {
    public WorkbookSchema {
        sheets = sheets == null ? List.of() : List.copyOf(sheets);
    }
}
