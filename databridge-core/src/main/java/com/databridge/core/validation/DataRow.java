package com.databridge.core.validation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

/**
 * One data row of a sheet, held in memory only while a submission is processed. Values are keyed
 * by caption as written in the sheet and are one of: String, BigDecimal, LocalDate,
 * LocalDateTime, LocalTime or Boolean. Empty cells are absent.
 */
public record DataRow(int rowNo, Map<String, Object> values) {

    public Object get(String caption) {
        return values.get(caption);
    }

    /** The value as text, the way a person would type it; null when empty. */
    public String text(String caption) {
        return text(values.get(caption));
    }

    public static String text(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal d) {
            BigDecimal s = d.stripTrailingZeros();
            return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
        }
        if (v instanceof LocalDate || v instanceof LocalDateTime || v instanceof LocalTime) return v.toString();
        return v.toString();
    }

    /** Text with surrounding spaces removed, for comparing keys; null when empty. */
    public String key(String caption) {
        String t = text(caption);
        if (t == null) return null;
        t = t.trim();
        return t.isEmpty() ? null : t;
    }
}
