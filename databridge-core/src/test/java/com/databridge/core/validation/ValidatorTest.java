package com.databridge.core.validation;

import com.databridge.core.domain.TemplateKind;
import com.databridge.core.finding.Finding;
import com.databridge.core.finding.Severity;
import com.databridge.core.finding.ValueMasker;
import com.databridge.core.schema.FieldDef;
import com.databridge.core.schema.FieldSource;
import com.databridge.core.schema.FieldType;
import com.databridge.core.schema.OptionValue;
import com.databridge.core.schema.TableSchema;
import com.databridge.core.setup.FindingValueMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidatorTest {

    private static FieldDef field(int i, String caption, FieldType t, Integer len, String... options) {
        List<OptionValue> o = new ArrayList<>();
        for (int k = 0; k < options.length; k++) o.add(new OptionValue(k, options[k]));
        return new FieldDef(i, String.valueOf((char) ('A' + i)), caption, t, len, o, false, FieldSource.TEMPLATE_COMMENT);
    }

    private static DataRow row(int n, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
        return new DataRow(n, m);
    }

    private static SheetData sheet(TemplateKind k, String name, List<FieldDef> fields, DataRow... rows) {
        return new SheetData(k, new TableSchema(name, null, name, 1, null, 3, 4, fields, List.of()), List.of(rows));
    }

    private static RuleDef rule(String id, RuleType t, String sheet, String field) {
        RuleDef r = new RuleDef();
        r.setId(id);
        r.setType(t);
        r.setSheet(sheet);
        r.setField(field);
        return r;
    }

    private static List<Finding> run(List<RuleDef> rules, SheetData... sheets) {
        return new Validator(rules, new ValueMasker(FindingValueMode.MASKED)).validate(List.of(sheets)).findings();
    }

    private final List<FieldDef> members = List.of(
            field(0, "No.", FieldType.CODE, 20), field(1, "Name", FieldType.TEXT, 10),
            field(2, "Gender", FieldType.OPTION, null, "", "Male", "Female"),
            field(3, "Method", FieldType.OPTION, null, "Amortised", "Reducing Balance"),
            field(4, "Born", FieldType.DATE, null), field(5, "Age", FieldType.INTEGER, null),
            field(6, "Amount", FieldType.DECIMAL, null), field(7, "Active", FieldType.BOOLEAN, null),
            field(8, "Every", FieldType.DATEFORMULA, null));

    @Test
    void cleanRowHasNoFindings() {
        SheetData s = sheet(TemplateKind.STATIC, "Members", members,
                row(4, "No.", "200001", "Name", "JANE", "Gender", "Female", "Method", "Amortised", "Born", LocalDate.of(1990, 1, 2),
                        "Age", new BigDecimal("36"), "Amount", "1200.50", "Active", "true", "Every", "1M"),
                row(5, "No.", "200002", "Born", "1990-02-28", "Age", "40", "Active", true, "Every", "12M"),
                row(6, "No.", "200003", "Born", "28/02/1990"));
        assertTrue(run(List.of(), s).isEmpty(), () -> run(List.of(), s).toString());
    }

    @Test
    void typeLengthAndOptionProblems() {
        SheetData s = sheet(TemplateKind.STATIC, "Members", members,
                row(4, "No.", "200001", "Name", "JOSEPHINE WANJIKU", "Gender", "M", "Method", "Amortized", "Born", "31/02/1990",
                        "Age", "254700000000", "Amount", "1,000", "Active", "maybe", "Every", "monthly"),
                row(5, "No.", "200002", "Gender", "FEMALE"));
        List<Finding> f = run(List.of(), s);
        Map<String, Finding> byField = new LinkedHashMap<>();
        f.forEach(x -> byField.put(x.row() + ":" + x.field(), x));
        assertEquals("LENGTH", byField.get("4:Name").ruleType());
        assertEquals(Severity.CRITICAL, byField.get("4:Gender").severity());
        assertEquals(Severity.WARNING, byField.get("4:Method").severity(), "near match is a fixable warning");
        assertTrue(byField.get("4:Method").message().contains("'Amortised'"));
        assertTrue(byField.get("4:Method").autoFixable());
        assertEquals("DATE", byField.get("4:Born").ruleType());
        assertTrue(byField.get("4:Age").message().contains("too large"), "BC Integer range");
        assertEquals("NUMBER", byField.get("4:Amount").ruleType());
        assertEquals("BOOLEAN", byField.get("4:Active").ruleType());
        assertEquals("DATEFORMULA", byField.get("4:Every").ruleType());
        assertEquals(Severity.WARNING, byField.get("5:Gender").severity(), "capitals only");
        assertEquals(9, f.size());
    }

    @Test
    void messagesNeverQuoteClientValuesAndValuesAreMasked() {
        SheetData s = sheet(TemplateKind.STATIC, "Members", members, row(4, "No.", "200001", "Name", "WANJIRU NJOROGE KAMAU"));
        Finding f = run(List.of(), s).get(0);
        assertFalse(f.message().contains("WANJIRU"));
        assertEquals("WA*****************AU", f.value());
    }

    @Test
    void formatUniqueRequiredAndCompare() {
        List<FieldDef> fields = List.of(field(0, "No.", FieldType.CODE, 20), field(1, "PIN", FieldType.CODE, 20),
                field(2, "Requested", FieldType.DECIMAL, null), field(3, "Approved", FieldType.DECIMAL, null),
                field(4, "Disbursed", FieldType.DATE, null), field(5, "Starts", FieldType.DATE, null));
        SheetData s = sheet(TemplateKind.STATIC, "Loans", fields,
                row(4, "No.", "L1", "PIN", "A123456789Z", "Requested", "100", "Approved", "100", "Disbursed", "2026-01-10", "Starts", "2026-02-01"),
                row(5, "No.", "L1", "PIN", "A12345678Z", "Requested", "100", "Approved", "150", "Disbursed", "2026-01-10", "Starts", "2026-01-01"),
                row(6, "PIN", "P123456789B"));
        RuleDef pin = rule("pin", RuleType.FORMAT, "Loans", "PIN");
        pin.setPattern("KRA_PIN");
        RuleDef uniq = rule("uniq", RuleType.UNIQUE, "Loans", "No.");
        RuleDef req = rule("req", RuleType.REQUIRED, "Loans", "No.");
        RuleDef amt = rule("amt", RuleType.COMPARE, "Loans", "Approved");
        amt.setOperator("LE");
        amt.setOtherField("Requested");
        amt.setSeverity(Severity.WARNING);
        RuleDef dates = rule("dates", RuleType.COMPARE, "Loans", "Starts");
        dates.setOperator("GE");
        dates.setOtherField("Disbursed");
        List<Finding> f = run(List.of(pin, uniq, req, amt, dates), s);
        assertEquals(List.of("pin@5", "uniq@5", "dates@5", "req@6", "amt@5"),
                f.stream().map(x -> x.ruleId() + "@" + x.row()).toList());
        assertTrue(f.get(2).message().endsWith("must be on or after Disbursed"));
        assertTrue(f.get(4).message().endsWith("must be no more than Requested"));
    }

    @Test
    void referencesWorkAcrossWorkbooksAndAreSkippedWhenTheTargetIsMissing() {
        SheetData loans = sheet(TemplateKind.STATIC, "Loans", List.of(field(0, "Loan No.", FieldType.CODE, 20)),
                row(4, "Loan No.", "BSL1"), row(5, "Loan No.", " bsl2 "));
        SheetData balances = sheet(TemplateKind.TRANSACTIONAL, "BOSA LOANS", List.of(field(0, "Loan Number", FieldType.CODE, 20)),
                row(2, "Loan Number", "BSL2"), row(3, "Loan Number", "BSL9"));
        RuleDef ref = rule("ref", RuleType.REFERENCE, "BOSA LOANS", "Loan Number");
        ref.setTargetSheet("Loans");
        ref.setTargetField("Loan No.");
        List<Finding> f = run(List.of(ref), loans, balances);
        assertEquals(1, f.size());
        assertEquals(3, f.get(0).row());
        assertEquals("TRANSACTIONAL", f.get(0).kind());

        ValidationResult alone = new Validator(List.of(ref), new ValueMasker(FindingValueMode.MASKED)).validate(List.of(balances));
        assertTrue(alone.findings().isEmpty());
        assertEquals("ref", alone.skipped().get(0).ruleId());
    }

    @Test
    void groupTotalsAreReportedOnTheFirstRowOfTheGroup() {
        List<FieldDef> fields = List.of(field(0, "Member", FieldType.CODE, 20), field(1, "Share", FieldType.DECIMAL, null),
                field(2, "Nominee", FieldType.BOOLEAN, null));
        SheetData s = sheet(TemplateKind.STATIC, "Nominees", fields,
                row(4, "Member", "1", "Share", "60", "Nominee", "true"), row(5, "Member", "1", "Share", "30", "Nominee", "true"),
                row(6, "Member", "2", "Share", "100", "Nominee", "true"), row(7, "Member", "2", "Share", "50", "Nominee", "false"));
        RuleDef sum = rule("sum", RuleType.SUM_EQUALS, "Nominees", "Share");
        sum.setGroupBy("Member");
        sum.setWhereField("Nominee");
        sum.setWhereEquals("true");
        sum.setExpected(new BigDecimal("100"));
        List<Finding> f = run(List.of(sum), s);
        assertEquals(1, f.size());
        assertEquals(4, f.get(0).row());
        assertTrue(f.get(0).message().contains("totals 90, expected 100"));
    }

    @Test
    void readinessCountsRowsWithoutCriticalFindings() {
        SheetData s = sheet(TemplateKind.STATIC, "Members", members, row(4, "No.", "1", "Gender", "M"), row(5, "No.", "2"),
                row(6, "No.", "3", "Gender", "MALE"), row(7, "No.", "4"));
        ValidationResult r = new Validator(List.of(), new ValueMasker(FindingValueMode.MASKED)).validate(List.of(s));
        assertEquals(0.75, r.readiness(), 1e-9);
        assertEquals(1, r.sheets().get(0).rowsWithCritical());
        assertEquals(1, r.sheets().get(0).rowsWithWarning());
    }

    @Test
    void shippedRulesAreValid() {
        List<RuleDef> rules = RuleLoader.defaults();
        assertTrue(rules.size() >= 40);
        rules.forEach(com.databridge.core.service.RuleServiceTestAccess::validate);
    }
}
