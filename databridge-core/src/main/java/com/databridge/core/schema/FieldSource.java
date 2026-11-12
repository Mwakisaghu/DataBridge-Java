package com.databridge.core.schema;

/** Where a field's type came from. */
public enum FieldSource {
    /** The header-cell comment in the template. */
    TEMPLATE_COMMENT,
    /** A Template Setup entry (wins over the comment). */
    TEMPLATE_SETUP,
    /** Neither: the type is unknown. */
    NONE
}
