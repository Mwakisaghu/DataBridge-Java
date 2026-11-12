package com.databridge.core.schema;

/** One value of a BC Option field: its ordinal and caption (the caption may be blank). */
public record OptionValue(int ordinal, String caption) { }
