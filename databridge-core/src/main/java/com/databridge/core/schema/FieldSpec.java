package com.databridge.core.schema;

import java.util.List;

/** A parsed type: kind, maximum length for Code/Text, and option values for Option. */
public record FieldSpec(FieldType type, Integer length, List<OptionValue> options) {

    public FieldSpec {
        options = options == null ? List.of() : List.copyOf(options);
    }

    public static FieldSpec unknown() {
        return new FieldSpec(FieldType.UNKNOWN, null, List.of());
    }

    public static FieldSpec of(FieldType type) {
        return new FieldSpec(type, null, List.of());
    }

    public boolean isKnown() {
        return type != FieldType.UNKNOWN;
    }
}
