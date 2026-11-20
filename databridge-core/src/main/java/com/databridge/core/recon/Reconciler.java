package com.databridge.core.recon;

import com.databridge.core.finding.Finding;
import com.databridge.core.finding.Severity;
import com.databridge.core.schema.FieldDef;
import com.databridge.core.setup.TemplateSetup;
import com.databridge.core.validation.DataRow;
import com.databridge.core.validation.SheetData;
import com.databridge.core.validation.SkippedRule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reconciles a submission's opening balances: the trial balance must balance, and every control
 * account must equal the total of its subledgers. Each line that does not tie becomes a Critical
 * finding on the trial-balance row concerned, which a person explains and Finance approves.
 *
 * <p>Messages carry account-level totals and variances (never a member's or customer's own
 * figures): a reconciliation cannot be reviewed without them. See ADR 0007.
 */
public final class Reconciler {

    public static final String TB_RULE = "recon.trial-balance";
    public static final String CONTROL_RULE = "recon.control.";

    private final ReconSetup setup;

    public Reconciler(ReconSetup setup) {
        this.setup = setup;
    }

    public ReconResult reconcile(List<SheetData> sheets) {
        Map<String, SheetData> byName = new LinkedHashMap<>();
        for (SheetData s : sheets) byName.putIfAbsent(TemplateSetup.normalise(s.name()), s);
        TrialBalanceDef tbDef = setup.getTrialBalance();
        List<ReconLine> lines = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        List<SkippedRule> skipped = new ArrayList<>();

        SheetData tb = byName.get(TemplateSetup.normalise(tbDef.getSheet()));
        Optional<FieldDef> acc = tb == null ? Optional.empty() : tb.field(tbDef.getAccountField());
        Optional<FieldDef> dr = tb == null ? Optional.empty() : tb.field(tbDef.getDebitField());
        Optional<FieldDef> cr = tb == null ? Optional.empty() : tb.field(tbDef.getCreditField());
        if (tb == null || acc.isEmpty() || dr.isEmpty() || cr.isEmpty()) {
            String why = tb == null ? "Sheet '" + tbDef.getSheet() + "' was not in this submission"
                    : "Sheet '" + tb.name() + "' needs the columns '" + tbDef.getAccountField() + "', '"
                      + tbDef.getDebitField() + "' and '" + tbDef.getCreditField() + "'";
            skipped.add(new SkippedRule(TB_RULE, why));
            lines.add(new ReconLine(1, ReconLine.Type.TB, null, null, "Trial balance", null, null, null,
                    ReconLine.Status.NOT_CHECKED, tbDef.getSheet(), why));
            for (ControlDef c : enabled()) {
                skipped.add(new SkippedRule(CONTROL_RULE + c.getId(), "No trial balance to compare with"));
                lines.add(new ReconLine(lines.size() + 1, ReconLine.Type.CONTROL, c.getId(), c.getGlAccount(), c.getName(),
                        null, null, null, ReconLine.Status.NOT_CHECKED, sources(c), "No trial balance to compare with"));
            }
            return new ReconResult(lines, findings, skipped);
        }

        // Trial balance totals, and each account's row and balance.
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        Map<String, DataRow> rowOf = new LinkedHashMap<>();
        DataRow last = null;
        for (DataRow r : tb.rows()) {
            String account = r.key(acc.get().caption());
            if (account == null) continue; // blank or totals rows
            debits = debits.add(amount(r.get(dr.get().caption())));
            credits = credits.add(amount(r.get(cr.get().caption())));
            rowOf.putIfAbsent(account, r);
            last = r;
        }
        debits = money(debits);
        credits = money(credits);
        BigDecimal tbVar = debits.subtract(credits);
        boolean balanced = tbVar.abs().compareTo(tbDef.getTolerance()) <= 0;
        lines.add(new ReconLine(1, ReconLine.Type.TB, null, null, "Trial balance", debits, credits, tbVar,
                balanced ? ReconLine.Status.TIES : ReconLine.Status.VARIANCE, tb.name(), null));
        if (!balanced && last != null) {
            FieldDef f = tbVar.signum() > 0 ? dr.get() : cr.get();
            findings.add(finding(tb, last.rowNo(), f, TB_RULE, "TB_BALANCE",
                    (tbVar.signum() > 0 ? "Debits exceed credits by " : "Credits exceed debits by ") + fmt(tbVar.abs()), tbVar));
        }

        for (ControlDef c : enabled()) {
            BigDecimal sub = BigDecimal.ZERO;
            String missing = null;
            for (SourceDef s : c.getSources()) {
                SheetData sd = byName.get(TemplateSetup.normalise(s.getSheet()));
                Optional<FieldDef> amt = sd == null ? Optional.empty() : sd.field(s.getAmountField());
                Optional<FieldDef> where = sd == null || s.getWhereField() == null ? Optional.empty() : sd.field(s.getWhereField());
                if (sd == null) {
                    missing = "Sheet '" + s.getSheet() + "' was not in this submission";
                } else if (amt.isEmpty() || (s.getWhereField() != null && where.isEmpty())) {
                    missing = "Sheet '" + sd.name() + "' has no column '" + (amt.isEmpty() ? s.getAmountField() : s.getWhereField()) + "'";
                }
                if (missing != null) break;
                for (DataRow r : sd.rows()) {
                    if (where.isPresent()) {
                        String w = r.key(where.get().caption());
                        if (w == null) continue;
                        if (s.getWhereEquals() != null && !w.equalsIgnoreCase(s.getWhereEquals().trim())) continue;
                        if (s.getWhereEndsWith() != null && !w.endsWith(s.getWhereEndsWith().trim())) continue;
                    }
                    sub = sub.add(amount(r.get(amt.get().caption())));
                }
            }
            if (missing != null) {
                skipped.add(new SkippedRule(CONTROL_RULE + c.getId(), missing));
                lines.add(new ReconLine(lines.size() + 1, ReconLine.Type.CONTROL, c.getId(), c.getGlAccount(), c.getName(),
                        null, null, null, ReconLine.Status.NOT_CHECKED, sources(c), missing));
                continue;
            }
            sub = money(sub);
            DataRow glRow = rowOf.get(c.getGlAccount().trim());
            BigDecimal gl = BigDecimal.ZERO;
            if (glRow != null) {
                BigDecimal d = amount(glRow.get(dr.get().caption()));
                BigDecimal k = amount(glRow.get(cr.get().caption()));
                gl = money(c.getNormal() == Side.DEBIT ? d.subtract(k) : k.subtract(d));
            }
            BigDecimal var = sub.subtract(gl);
            boolean ties = var.abs().compareTo(c.getTolerance()) <= 0;
            String note = glRow == null ? "GL " + c.getGlAccount() + " is not in the trial balance" : null;
            lines.add(new ReconLine(lines.size() + 1, ReconLine.Type.CONTROL, c.getId(), c.getGlAccount(), c.getName(),
                    gl, sub, var, ties ? ReconLine.Status.TIES : ReconLine.Status.VARIANCE, sources(c), note));
            if (!ties) {
                String label = "GL " + c.getGlAccount() + (c.getName() == null ? "" : " " + c.getName());
                String msg = glRow == null
                        ? label + " is not in the trial balance; its subledger totals " + fmt(sub)
                        : label + " is " + fmt(var.abs()) + (var.signum() > 0 ? " below" : " above") + " its subledger";
                FieldDef f = c.getNormal() == Side.DEBIT ? dr.get() : cr.get();
                findings.add(finding(tb, glRow == null ? (last == null ? 0 : last.rowNo()) : glRow.rowNo(), f,
                        CONTROL_RULE + c.getId(), "CONTROL", msg, var));
            }
        }
        return new ReconResult(lines, findings, skipped);
    }

    private List<ControlDef> enabled() {
        return setup.getControls().stream().filter(ControlDef::isEnabled).toList();
    }

    private static String sources(ControlDef c) {
        return String.join("; ", c.getSources().stream().map(SourceDef::describe).toList());
    }

    /**
     * The variance goes into the finding's value: an explanation approved for one variance then
     * carries forward to the next check only while the variance stays exactly the same.
     */
    private static Finding finding(SheetData tb, int row, FieldDef f, String ruleId, String type, String message,
                                   BigDecimal variance) {
        return Finding.open(Severity.CRITICAL, tb.kind() == null ? null : tb.kind().name(), tb.name(), row, f.column(),
                f.caption(), ruleId, type, message, fmt(variance), false);
    }

    /** A cell as an amount: numbers as they are, text with thousands separators or brackets for negatives. */
    static BigDecimal amount(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal d) return d;
        String t = DataRow.text(v).trim().replace(",", "").replace(" ", "");
        if (t.isEmpty() || t.equals("-")) return BigDecimal.ZERO;
        boolean neg = t.startsWith("(") && t.endsWith(")");
        if (neg) t = t.substring(1, t.length() - 1);
        try {
            BigDecimal d = new BigDecimal(t);
            return neg ? d.negate() : d;
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO; // the schema layer reports non-numbers as NUMBER findings
        }
    }

    static BigDecimal money(BigDecimal d) {
        return d.setScale(2, RoundingMode.HALF_UP);
    }

    public static String fmt(BigDecimal d) {
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
        return f.format(d);
    }
}
