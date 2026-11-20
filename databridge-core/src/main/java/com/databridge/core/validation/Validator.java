package com.databridge.core.validation;

import com.databridge.core.finding.Finding;
import com.databridge.core.finding.Severity;
import com.databridge.core.finding.ValueMasker;
import com.databridge.core.schema.FieldDef;
import com.databridge.core.schema.FieldType;
import com.databridge.core.schema.OptionValue;
import com.databridge.core.setup.TemplateSetup;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks the sheets of one submission. Two layers:
 * <ol>
 *   <li>schema checks derived from each column's BC type: required, length, number, date,
 *       yes/no, date formula and option values;</li>
 *   <li>the Rule Setup: formats, uniqueness, references (also across the workbooks of the
 *       submission), group totals and field comparisons.</li>
 * </ol>
 * Messages never quote client values; the offending value goes, masked, into the finding's value.
 */
public final class Validator {

    static final String SCHEMA_REQUIRED = "schema.required";
    static final String SCHEMA_LENGTH = "schema.length";
    static final String SCHEMA_TYPE = "schema.type";
    static final String SCHEMA_OPTION = "schema.option";

    private static final Set<String> BOOLEANS = Set.of("true", "false", "yes", "no", "1", "0");
    private static final Pattern DATE_FORMULA = Pattern.compile("^[-+]?(\\d+[DWMQY]|C[DWMQY])([-+]\\d*[DWMQY]|[-+]C[DWMQY])*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GUID = Pattern.compile("^\\{?[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}}?$");
    private static final List<DateTimeFormatter> DATE_TEXT = List.of(
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu/M/d").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d-M-uuuu").withResolverStyle(ResolverStyle.STRICT));

    private final List<RuleDef> rules;
    private final ValueMasker masker;

    public Validator(List<RuleDef> rules, ValueMasker masker) {
        this.rules = rules.stream().filter(RuleDef::isEnabled).toList();
        this.masker = masker;
    }

    public ValidationResult validate(List<SheetData> sheets) {
        List<Finding> findings = new ArrayList<>();
        List<SkippedRule> skipped = new ArrayList<>();
        Map<String, SheetData> byName = new LinkedHashMap<>();
        for (SheetData s : sheets) byName.putIfAbsent(TemplateSetup.normalise(s.name()), s);

        for (SheetData s : sheets) schema(s, findings);
        for (RuleDef r : rules) {
            SheetData s = byName.get(TemplateSetup.normalise(r.getSheet()));
            if (s == null) {
                skipped.add(new SkippedRule(r.getId(), "Sheet '" + r.getSheet() + "' was not in this submission"));
                continue;
            }
            Optional<FieldDef> f = s.field(r.getField());
            if (f.isEmpty()) {
                skipped.add(new SkippedRule(r.getId(), "Sheet '" + s.name() + "' has no column '" + r.getField() + "'"));
                continue;
            }
            switch (r.getType()) {
                case REQUIRED: required(r, s, f.get(), findings); break;
                case FORMAT: format(r, s, f.get(), findings); break;
                case UNIQUE: unique(r, s, f.get(), findings); break;
                case REFERENCE: reference(r, s, f.get(), byName, findings, skipped); break;
                case SUM_EQUALS: sumEquals(r, s, f.get(), findings, skipped); break;
                case COMPARE: compare(r, s, f.get(), findings, skipped); break;
                default: skipped.add(new SkippedRule(r.getId(), "Unknown rule type"));
            }
        }
        sort(findings);
        return new ValidationResult(findings, stats(sheets, findings), skipped);
    }

    /** A result with more findings and skipped checks added, e.g. from reconciliation; sheet quality recomputed. */
    public static ValidationResult combine(List<SheetData> sheets, ValidationResult r, List<Finding> more, List<SkippedRule> moreSkipped) {
        if (more.isEmpty() && moreSkipped.isEmpty()) return r;
        List<Finding> all = new ArrayList<>(r.findings());
        all.addAll(more);
        sort(all);
        List<SkippedRule> skipped = new ArrayList<>(r.skipped());
        skipped.addAll(moreSkipped);
        return new ValidationResult(all, stats(sheets, all), skipped);
    }

    private static void sort(List<Finding> findings) {
        findings.sort((a, b) -> {
            int c = a.severity().compareTo(b.severity());
            if (c == 0) c = String.valueOf(a.sheet()).compareTo(String.valueOf(b.sheet()));
            return c != 0 ? c : Integer.compare(a.row(), b.row());
        });
    }

    // ------------------------------------------------------------------ schema layer

    private void schema(SheetData s, List<Finding> out) {
        for (DataRow row : s.rows()) {
            for (FieldDef f : s.schema().fields()) {
                Object v = row.get(f.caption());
                if (v == null) {
                    if (f.required()) {
                        out.add(finding(Severity.CRITICAL, s, row, f, SCHEMA_REQUIRED, "REQUIRED", f.caption() + " is required", null, false));
                    }
                    continue;
                }
                String problem = typeProblem(f, v);
                if (problem != null) {
                    String type = f.type() == FieldType.DATE ? "DATE" : f.type() == FieldType.BOOLEAN ? "BOOLEAN"
                            : f.type() == FieldType.DATEFORMULA ? "DATEFORMULA" : "NUMBER";
                    out.add(finding(Severity.CRITICAL, s, row, f, SCHEMA_TYPE, type, problem, v, false));
                    continue;
                }
                String text = DataRow.text(v);
                if ((f.type() == FieldType.CODE || f.type() == FieldType.TEXT) && f.length() != null && text.length() > f.length()) {
                    out.add(finding(Severity.CRITICAL, s, row, f, SCHEMA_LENGTH, "LENGTH",
                            f.caption() + " has " + text.length() + " characters; " + typeName(f) + " allows " + f.length(), v, false));
                }
                if (f.type() == FieldType.OPTION && !f.options().isEmpty()) option(s, row, f, text, v, out);
            }
        }
    }

    /** A sentence describing why v cannot be stored in the field's BC type, or null. */
    static String typeProblem(FieldDef f, Object v) {
        String t = DataRow.text(v).trim();
        switch (f.type()) {
            case DECIMAL:
                return number(v) == null ? f.caption() + " is not a number" : null;
            case INTEGER:
            case BIGINTEGER: {
                BigDecimal n = number(v);
                if (n == null) return f.caption() + " is not a number";
                if (n.stripTrailingZeros().scale() > 0) return f.caption() + " must be a whole number";
                BigDecimal max = BigDecimal.valueOf(f.type() == FieldType.INTEGER ? Integer.MAX_VALUE : Long.MAX_VALUE);
                if (n.abs().compareTo(max) > 0) {
                    return f.caption() + " is too large for a Business Central " + (f.type() == FieldType.INTEGER ? "Integer" : "BigInteger") + " field";
                }
                return null;
            }
            case BOOLEAN:
                return v instanceof Boolean || BOOLEANS.contains(t.toLowerCase(Locale.ROOT)) ? null : f.caption() + " is not a yes/no value";
            case DATE:
                return date(v) == null ? f.caption() + " is not a valid date" : null;
            case DATEFORMULA:
                return DATE_FORMULA.matcher(t).matches() ? null : f.caption() + " is not a date formula such as 1M or 12M";
            case GUID:
                return GUID.matcher(t).matches() ? null : f.caption() + " is not a GUID";
            default:
                return null;
        }
    }

    private void option(SheetData s, DataRow row, FieldDef f, String text, Object v, List<Finding> out) {
        List<String> captions = f.options().stream().map(OptionValue::caption).toList();
        if (captions.contains(text)) return;
        String trimmed = text.trim();
        for (String c : captions) {
            if (c.equalsIgnoreCase(trimmed)) {
                out.add(finding(Severity.WARNING, s, row, f, SCHEMA_OPTION, "OPTION",
                        f.caption() + " differs from the option '" + c + "' only in capitals or spaces", v, true));
                return;
            }
        }
        if (trimmed.length() >= 5) {
            for (String c : captions) {
                if (c.length() >= 5 && distance(c.toLowerCase(Locale.ROOT), trimmed.toLowerCase(Locale.ROOT)) <= 2) {
                    out.add(finding(Severity.WARNING, s, row, f, SCHEMA_OPTION, "OPTION",
                            f.caption() + " is not an option value; Business Central has '" + c + "'", v, true));
                    return;
                }
            }
        }
        out.add(finding(Severity.CRITICAL, s, row, f, SCHEMA_OPTION, "OPTION", f.caption() + " is not one of the "
                + captions.stream().filter(c -> !c.isEmpty()).count() + " option values Business Central accepts", v, false));
    }

    // ------------------------------------------------------------------ Rule Setup layer

    private void required(RuleDef r, SheetData s, FieldDef f, List<Finding> out) {
        if (f.required()) return; // already reported by the schema layer
        for (DataRow row : s.rows()) {
            if (row.key(f.caption()) == null) out.add(finding(r, s, row, f, "REQUIRED", f.caption() + " is required", null));
        }
    }

    private void format(RuleDef r, SheetData s, FieldDef f, List<Finding> out) {
        Pattern p = Patterns.compile(r.getPattern());
        String described = Patterns.describe(r.getPattern());
        for (DataRow row : s.rows()) {
            String v = row.key(f.caption());
            if (v != null && !p.matcher(v).matches()) {
                out.add(finding(r, s, row, f, "FORMAT", f.caption() + " is not " + described, v));
            }
        }
    }

    private void unique(RuleDef r, SheetData s, FieldDef f, List<Finding> out) {
        Map<String, Integer> first = new HashMap<>();
        for (DataRow row : s.rows()) {
            String v = row.key(f.caption());
            if (v == null) continue;
            Integer at = first.putIfAbsent(v.toUpperCase(Locale.ROOT), row.rowNo());
            if (at != null) out.add(finding(r, s, row, f, "DUPLICATE", f.caption() + " is the same as on row " + at, v));
        }
    }

    private void reference(RuleDef r, SheetData s, FieldDef f, Map<String, SheetData> byName,
                           List<Finding> out, List<SkippedRule> skipped) {
        SheetData target = byName.get(TemplateSetup.normalise(r.getTargetSheet()));
        if (target == null) {
            skipped.add(new SkippedRule(r.getId(), "Needs sheet '" + r.getTargetSheet() + "' in the same submission"));
            return;
        }
        Optional<FieldDef> tf = target.field(r.getTargetField());
        if (tf.isEmpty()) {
            skipped.add(new SkippedRule(r.getId(), "Sheet '" + target.name() + "' has no column '" + r.getTargetField() + "'"));
            return;
        }
        Set<String> keys = new HashSet<>();
        for (DataRow row : target.rows()) {
            String k = row.key(tf.get().caption());
            if (k != null) keys.add(k.toUpperCase(Locale.ROOT));
        }
        for (DataRow row : s.rows()) {
            String v = row.key(f.caption());
            if (v != null && !keys.contains(v.toUpperCase(Locale.ROOT))) {
                out.add(finding(r, s, row, f, "REFERENCE", f.caption() + " is not found in " + target.name()
                        + " (" + tf.get().caption() + ")", v));
            }
        }
    }

    private void sumEquals(RuleDef r, SheetData s, FieldDef f, List<Finding> out, List<SkippedRule> skipped) {
        Optional<FieldDef> group = s.field(r.getGroupBy());
        if (group.isEmpty() || r.getExpected() == null) {
            skipped.add(new SkippedRule(r.getId(), "Needs a groupBy column that exists and an expected total"));
            return;
        }
        Optional<FieldDef> where = r.getWhereField() == null ? Optional.empty() : s.field(r.getWhereField());
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        Map<String, DataRow> firstRow = new HashMap<>();
        for (DataRow row : s.rows()) {
            String g = row.key(group.get().caption());
            if (g == null) continue;
            if (where.isPresent()) {
                String w = row.key(where.get().caption());
                if (w == null || !w.equalsIgnoreCase(r.getWhereEquals())) continue;
            }
            BigDecimal n = number(row.get(f.caption()));
            totals.merge(g, n == null ? BigDecimal.ZERO : n, BigDecimal::add);
            firstRow.putIfAbsent(g, row);
        }
        for (Map.Entry<String, BigDecimal> e : totals.entrySet()) {
            if (e.getValue().compareTo(r.getExpected()) != 0) {
                out.add(finding(r, s, firstRow.get(e.getKey()), f, "RULE", f.caption() + " for this " + group.get().caption()
                        + " totals " + plain(e.getValue()) + ", expected " + plain(r.getExpected()), e.getKey()));
            }
        }
    }

    private void compare(RuleDef r, SheetData s, FieldDef f, List<Finding> out, List<SkippedRule> skipped) {
        Optional<FieldDef> other = s.field(r.getOtherField());
        if (other.isEmpty() || r.getOperator() == null) {
            skipped.add(new SkippedRule(r.getId(), "Needs an operator and an otherField column that exists"));
            return;
        }
        String op = r.getOperator().toUpperCase(Locale.ROOT);
        for (DataRow row : s.rows()) {
            Object a = row.get(f.caption());
            Object b = row.get(other.get().caption());
            if (a == null || b == null) continue;
            Integer c = compareValues(a, b);
            if (c == null) continue; // type problems are reported by the schema layer
            boolean ok = switch (op) {
                case "LT" -> c < 0;
                case "LE" -> c <= 0;
                case "EQ" -> c == 0;
                case "GE" -> c >= 0;
                case "GT" -> c > 0;
                default -> true;
            };
            if (!ok) {
                boolean dates = number(a) == null;
                out.add(finding(r, s, row, f, "RULE", f.caption() + " must be " + words(op, dates) + " " + other.get().caption(), a));
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private Finding finding(RuleDef r, SheetData s, DataRow row, FieldDef f, String type, String message, Object value) {
        return finding(r.getSeverity(), s, row, f, r.getId(), type, message, value, r.isAutoFixable());
    }

    private Finding finding(Severity sev, SheetData s, DataRow row, FieldDef f, String ruleId, String type, String message,
                            Object value, boolean fixable) {
        return Finding.open(sev, s.kind() == null ? null : s.kind().name(), s.name(), row.rowNo(), f.column(), f.caption(),
                ruleId, type, message, masker.mask(DataRow.text(value)), fixable);
    }

    private static List<SheetStat> stats(List<SheetData> sheets, List<Finding> findings) {
        List<SheetStat> out = new ArrayList<>();
        for (SheetData s : sheets) {
            String kind = s.kind() == null ? null : s.kind().name();
            List<Finding> mine = findings.stream()
                    .filter(f -> s.name().equals(f.sheet()) && java.util.Objects.equals(kind, f.kind())).toList();
            Set<Integer> crit = new HashSet<>();
            Set<Integer> warn = new HashSet<>();
            int c = 0, w = 0, i = 0;
            for (Finding f : mine) {
                switch (f.severity()) {
                    case CRITICAL: c++; crit.add(f.row()); break;
                    case WARNING: w++; warn.add(f.row()); break;
                    default: i++;
                }
            }
            warn.removeAll(crit);
            out.add(new SheetStat(kind, s.name(), s.schema().tableId(), s.rows().size(), crit.size(), warn.size(), c, w, i));
        }
        return out;
    }

    static BigDecimal number(Object v) {
        if (v instanceof BigDecimal d) return d;
        if (v instanceof Boolean || v instanceof LocalDate || v instanceof LocalDateTime) return null;
        if (v == null) return null;
        try {
            return new BigDecimal(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static LocalDate date(Object v) {
        if (v instanceof LocalDate d) return d;
        if (v instanceof LocalDateTime t) return t.toLocalDate();
        if (!(v instanceof String s)) return null;
        String t = s.trim();
        if (t.length() > 10 && t.charAt(10) == 'T') t = t.substring(0, 10);
        for (DateTimeFormatter fmt : DATE_TEXT) {
            try {
                return LocalDate.parse(t, fmt);
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        return null;
    }

    private static Integer compareValues(Object a, Object b) {
        BigDecimal na = number(a), nb = number(b);
        if (na != null && nb != null) return na.compareTo(nb);
        LocalDate da = date(a), db = date(b);
        if (da != null && db != null) return da.compareTo(db);
        return null;
    }

    private static String words(String op, boolean dates) {
        return switch (op) {
            case "LT" -> dates ? "before" : "less than";
            case "LE" -> dates ? "on or before" : "no more than";
            case "EQ" -> dates ? "the same date as" : "equal to";
            case "GE" -> dates ? "on or after" : "no less than";
            case "GT" -> dates ? "after" : "more than";
            default -> op;
        };
    }

    private static String typeName(FieldDef f) {
        return (f.type() == FieldType.CODE ? "Code" : "Text") + f.length();
    }

    private static String plain(BigDecimal d) {
        BigDecimal s = d.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
