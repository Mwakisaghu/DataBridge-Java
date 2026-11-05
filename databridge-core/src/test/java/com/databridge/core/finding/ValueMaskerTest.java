package com.databridge.core.finding;

import com.databridge.core.setup.FindingValueMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ValueMaskerTest {

    @Test
    void maskedKeepsEndsOnly() {
        ValueMasker m = new ValueMasker(FindingValueMode.MASKED);
        assertEquals("A1********4Q", m.mask("A1239940094Q"));
        assertEquals("****", m.mask("0700"));
        assertEquals("", m.mask(""));
        assertNull(m.mask(null));
    }

    @Test
    void noneKeepsNothing() {
        assertNull(new ValueMasker(FindingValueMode.NONE).mask("22094380"));
    }

    @Test
    void fullKeepsValue() {
        assertEquals("22094380", new ValueMasker(FindingValueMode.FULL).mask("22094380"));
    }
}
