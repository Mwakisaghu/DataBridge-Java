package com.databridge.core.schema;

import java.util.List;

/**
 * The schema of one template sheet: its target BC table, where its captions sit,
 * and its columns. Holds template metadata only, never client data.
 */
public record TableSchema(
        String sheetName,
        String packageCode,
        String tableName,
        Integer tableId,
        String purpose,
        int captionRow,
        int dataStartRow,
        List<FieldDef> fields,
        List<String> warnings) {

    public TableSchema {
        fields = fields == null ? List.of() : List.copyOf(fields);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public long typedFieldCount() {
        return fields.stream().filter(f -> f.type() != FieldType.UNKNOWN).count();
    }

    public long optionFieldCount() {
        return fields.stream().filter(f -> f.type() == FieldType.OPTION).count();
    }
}
