package com.databridge.core.setup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** All sheet setups, matched to workbook sheets by normalised name. */
public class TemplateSetup {

    private final Map<String, SheetSetup> sheets = new LinkedHashMap<>();

    /** Adds or replaces the setup for a sheet; later definitions win. */
    public void put(SheetSetup sheet) {
        if (sheet.getSheetName() == null || sheet.getSheetName().isBlank()) {
            throw new SetupException("Every sheet setup needs a sheetName");
        }
        sheets.put(normalise(sheet.getSheetName()), sheet);
    }

    public Optional<SheetSetup> find(String sheetName) {
        return Optional.ofNullable(sheets.get(normalise(sheetName)));
    }

    public List<SheetSetup> all() {
        return Collections.unmodifiableList(new ArrayList<>(sheets.values()));
    }

    /** Trim, collapse whitespace, lower-case. Used for sheet names and captions alike. */
    public static String normalise(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
