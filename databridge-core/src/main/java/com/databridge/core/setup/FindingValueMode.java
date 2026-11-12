package com.databridge.core.setup;

/** How much of an offending client value a finding may carry. */
public enum FindingValueMode {
    /** Keep the first and last two characters, mask the rest (default). */
    MASKED,
    /** Keep the full value. Only for environments cleared to hold client data. */
    FULL,
    /** Keep no value at all. */
    NONE
}
