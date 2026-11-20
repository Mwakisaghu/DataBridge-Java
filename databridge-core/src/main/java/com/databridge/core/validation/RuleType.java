package com.databridge.core.validation;

/** Configurable rule kinds in the Rule Setup. Schema checks (type, length, option) are built in. */
public enum RuleType {
    /** The field must have a value. */
    REQUIRED,
    /** The value must match a named pattern (KRA_PIN, KE_PHONE, EMAIL, KE_ID) or a regular expression. */
    FORMAT,
    /** No two rows may share the value. */
    UNIQUE,
    /** The value must exist in another sheet's field, in this workbook or another one of the submission. */
    REFERENCE,
    /** Grouped by one field, the rows of another field must add up to an expected total. */
    SUM_EQUALS,
    /** One field compared with another field of the same row (numbers or dates). */
    COMPARE
}
