package com.databridge.core.finding;

import com.databridge.core.setup.FindingValueMode;

/** Applies the Finding Value Mode to a client value before it goes into a finding. */
public final class ValueMasker {

    private final FindingValueMode mode;

    public ValueMasker(FindingValueMode mode) {
        this.mode = mode;
    }

    public String mask(String value) {
        if (value == null) return null;
        switch (mode) {
            case NONE:
                return null;
            case FULL:
                return value;
            case MASKED:
            default:
                int n = value.length();
                if (n == 0) return value;
                if (n <= 4) return "*".repeat(n);
                return value.substring(0, 2) + "*".repeat(n - 4) + value.substring(n - 2);
        }
    }
}
