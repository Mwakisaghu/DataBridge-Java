package com.databridge.core.cleaning;

import com.databridge.core.schema.FieldDef;
import com.databridge.core.schema.FieldType;
import com.databridge.core.schema.OptionValue;
import com.databridge.core.schema.TableSchema;
import com.databridge.core.schema.WorkbookSchema;
import com.databridge.core.setup.TemplateSetup;
import com.databridge.core.validation.DataRow;
import com.databridge.core.validation.Validator;
import com.databridge.core.validation.WorkbookReader;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Applies the Cleaning Setup to a workbook in memory, cell by cell, keeping every cell's style and
 * the template's layout. Formula cells are never touched. Returns a change log without values.
 */
public final class Cleaner {

    private static final int SAMPLE_ROWS = 20;
    private static final Pattern SPACES = Pattern.compile("[ \\t\\u00A0]+");

    private final List<CleanRule> rules;

    public Cleaner(List<CleanRule> rules) {
        this.rules = rules.stream().filter(CleanRule::isEnabled).toList();
    }

    public CleanResult clean(Workbook wb, WorkbookSchema schema) {
        List<CleanChange> changes = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (TableSchema t : schema.sheets()) {
            Sheet sheet = wb.getSheet(t.sheetName());
            if (sheet == null || t.fields().isEmpty()) continue;
            for (CleanRule r : rules) {
                if (!r.getSheet().equals("*") && !TemplateSetup.normalise(r.getSheet()).equals(TemplateSetup.normalise(t.sheetName()))) continue;
                if (r.getAction() == CleanAction.REMOVE_DUPLICATE_ROWS) {
                    dedupe(sheet, t, r, changes);
                    continue;
                }
                List<FieldDef> targets = targets(t, r);
                if (targets.isEmpty() && !r.getField().equals("*")) {
                    skipped.add(r.getId() + ": sheet '" + t.sheetName() + "' has no column '" + r.getField() + "'");
                }
                for (FieldDef f : targets) column(sheet, t, f, r, changes);
            }
        }
        // Rules naming a sheet that was not in the workbook are not errors: a setup covers all templates.
        return new CleanResult(changes, skipped);
    }

    private static List<FieldDef> targets(TableSchema t, CleanRule r) {
        List<FieldDef> out = new ArrayList<>();
        for (FieldDef f : t.fields()) {
            boolean named = r.getField().equals("*") || TemplateSetup.normalise(r.getField()).equals(TemplateSetup.normalise(f.caption()));
            if (!named) continue;
            if (r.getAction() == CleanAction.OPTION_MATCH && (f.type() != FieldType.OPTION || f.options().isEmpty())) continue;
            out.add(f);
        }
        return out;
    }

    private void column(Sheet sheet, TableSchema t, FieldDef f, CleanRule r, List<CleanChange> out) {
        List<Integer> changed = new ArrayList<>();
        Pattern regex = r.getAction() == CleanAction.REPLACE ? Pattern.compile(r.getPattern()) : null;
        List<String> options = f.options().stream().map(OptionValue::caption).toList();
        for (int i = t.dataStartRow() - 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            Cell cell = row.getCell(f.columnIndex());
            if (cell != null && cell.getCellType() == CellType.FORMULA) continue;
            String before = text(cell);
            String after;
            if (r.getAction() == CleanAction.DEFAULT_VALUE) {
                if (before != null || !hasData(row, t)) continue;
                after = r.getValue();
            } else {
                if (before == null) continue;
                after = apply(r, before, cell, regex, options);
            }
            if (after == null || after.equals(before)) continue;
            if (cell == null) cell = row.createCell(f.columnIndex());
            boolean wasNumber = cell.getCellType() == CellType.NUMERIC;
            // Reset first: on an inline-string cell POI would otherwise write the new value beside
            // the old inline text, which Excel and readers keep showing. The style is kept.
            cell.setBlank();
            if (wasNumber && r.getAction() != CleanAction.PHONE_KE && r.getAction() != CleanAction.PAD_LEFT
                    && isNumber(after)) {
                cell.setCellValue(new BigDecimal(after).doubleValue());
            } else {
                cell.setCellValue(after);
            }
            changed.add(i + 1);
        }
        if (!changed.isEmpty()) out.add(change(t, f, r, changed));
    }

    private static String apply(CleanRule r, String v, Cell cell, Pattern regex, List<String> options) {
        boolean isText = cell != null && cell.getCellType() == CellType.STRING;
        switch (r.getAction()) {
            case TRIM:
                return isText ? SPACES.matcher(v).replaceAll(" ").strip() : v;
            case UPPER:
                return isText ? v.toUpperCase(Locale.ROOT) : v;
            case LOWER:
                return isText ? v.toLowerCase(Locale.ROOT) : v;
            case PHONE_KE:
                return phone(v);
            case OPTION_MATCH:
                return isText ? Validator.optionMatch(options, v).orElse(v) : v;
            case PAD_LEFT: {
                int len = r.getLength() == null ? 0 : r.getLength();
                String pad = r.getValue() == null || r.getValue().isEmpty() ? "0" : r.getValue().substring(0, 1);
                StringBuilder sb = new StringBuilder(v.strip());
                while (sb.length() < len) sb.insert(0, pad);
                return sb.toString();
            }
            case REPLACE:
                return isText ? regex.matcher(v).replaceAll(r.getReplacement() == null ? "" : r.getReplacement()) : v;
            default:
                return v;
        }
    }

    /** +254 followed by 9 digits starting 7 or 1, when the number can be read as Kenyan; otherwise unchanged. */
    static String phone(String v) {
        String d = v.replaceAll("[\\s\\-().]", "");
        if (d.startsWith("+")) d = d.substring(1);
        if (!d.chars().allMatch(Character::isDigit)) return v;
        String local;
        if (d.length() == 12 && d.startsWith("254")) local = d.substring(3);
        else if (d.length() == 10 && d.startsWith("0")) local = d.substring(1);
        else if (d.length() == 9) local = d;
        else return v;
        if (local.charAt(0) != '7' && local.charAt(0) != '1') return v;
        return "+254" + local;
    }

    private void dedupe(Sheet sheet, TableSchema t, CleanRule r, List<CleanChange> out) {
        Set<String> seen = new HashSet<>();
        List<Integer> emptied = new ArrayList<>();
        for (int i = t.dataStartRow() - 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || !hasData(row, t)) continue;
            StringBuilder key = new StringBuilder();
            for (FieldDef f : t.fields()) {
                String s = text(row.getCell(f.columnIndex()));
                key.append(s == null ? "" : s.strip()).append('\u0001');
            }
            if (seen.add(key.toString())) continue;
            for (FieldDef f : t.fields()) {
                Cell c = row.getCell(f.columnIndex());
                if (c != null) c.setBlank();
            }
            emptied.add(i + 1);
        }
        if (!emptied.isEmpty()) {
            out.add(new CleanChange(t.sheetName(), "(whole row)", null, r.getId(), r.getAction(), emptied.size(), sample(emptied)));
        }
    }

    private static boolean hasData(Row row, TableSchema t) {
        for (FieldDef f : t.fields()) if (WorkbookReader.value(row.getCell(f.columnIndex()), f.type()) != null) return true;
        return false;
    }

    /** The cell as text the way the reader sees it; null when empty. Dates are left to the reader. */
    private static String text(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.STRING) {
            String s = cell.getStringCellValue();
            return s == null || s.isEmpty() ? null : s;
        }
        Object v = WorkbookReader.value(cell, FieldType.UNKNOWN);
        return v == null ? null : DataRow.text(v);
    }

    private static boolean isNumber(String s) {
        try {
            new BigDecimal(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static CleanChange change(TableSchema t, FieldDef f, CleanRule r, List<Integer> rows) {
        return new CleanChange(t.sheetName(), f.caption(), f.column(), r.getId(), r.getAction(), rows.size(), sample(rows));
    }

    private static String sample(List<Integer> rows) {
        String s = String.join(",", rows.stream().limit(SAMPLE_ROWS).map(String::valueOf).toList());
        return rows.size() > SAMPLE_ROWS ? s + ",..." : s;
    }

    static Map<String, Integer> counts(List<CleanChange> changes) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (CleanChange c : changes) m.merge(c.ruleId(), c.cells(), Integer::sum);
        return m;
    }
}
