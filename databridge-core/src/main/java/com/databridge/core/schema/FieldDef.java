package com.databridge.core.schema;

import java.util.List;

/** One column of a sheet as the schema registry knows it. */
public record FieldDef(
        int columnIndex,
        String column,
        String caption,
        FieldType type,
        Integer length,
        List<OptionValue> options,
        boolean required,
        FieldSource source) {

    public FieldDef {
        options = options == null ? List.of() : List.copyOf(options);
    }
}
