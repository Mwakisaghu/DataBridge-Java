package com.databridge.core.cleaning;

/** What a cleaning rule does to a cell. Every action only ever changes a value it can be sure of. */
public enum CleanAction {
    /** Remove leading and trailing spaces and collapse runs of spaces inside text. */
    TRIM,
    /** Capital letters (KRA PINs, codes). */
    UPPER,
    /** Small letters (e-mail addresses). */
    LOWER,
    /** Kenyan phone numbers to +2547XXXXXXXX / +2541XXXXXXXX; numbers that cannot be read are left alone. */
    PHONE_KE,
    /** Option columns: the Business Central option when it is certain (case and spaces, or one close match). */
    OPTION_MATCH,
    /** Pad on the left to {@code length} with {@code value} (default 0), e.g. account numbers that lost leading zeros. */
    PAD_LEFT,
    /** Regular expression {@code pattern} replaced with {@code replacement}. */
    REPLACE,
    /** Empty cells filled with {@code value}. */
    DEFAULT_VALUE,
    /** Rows identical in every column to an earlier row are emptied (not deleted, so table ranges stay valid). */
    REMOVE_DUPLICATE_ROWS
}
