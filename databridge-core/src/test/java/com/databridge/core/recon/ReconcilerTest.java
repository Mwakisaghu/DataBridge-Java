package com.databridge.core.recon;

import com.databridge.core.finding.Finding;
import com.databridge.core.schema.FieldDef;
import com.databridge.core.schema.FieldSource;
import com.databridge.core.schema.FieldType;
import com.databridge.core.schema.TableSchema;
import com.databridge.core.domain.TemplateKind;
import com.databridge.core.validation.DataRow;
import com.databridge.core.validation.SheetData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconcilerTest {

    private static SheetData sheet(String name, String[] captions, Object[]... rows) {
        List<FieldDef> fields = new ArrayList<>();
        for (int i = 0; i < captions.length; i++) {
            fields.add(new FieldDef(i, String.valueOf((char) ('A' + i)), captions[i], FieldType.UNKNOWN, null, List.of(), false,
                    FieldSource.TEMPLATE_COMMENT));
        }
        List<DataRow> data = new ArrayList<>();
        int r = 2;
        for (Object[] row : rows) {
            Map<String, Object> v = new LinkedHashMap<>();
            for (int i = 0; i < row.length; i++) if (row[i] != null) v.put(captions[i], row[i]);
            data.add(new DataRow(r++, v));
        }
        return new SheetData(TemplateKind.TRANSACTIONAL, new TableSchema(name, null, name, null, null, 1, 2, fields, List.of()), data);
    }

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static SheetData tb(Object[]... rows) {
        return sheet("TRIAL BALANCE", new String[] {"No.", "Description", "Debit", "Credit"}, rows);
    }

    private static ReconSetup setup(ControlDef... controls) {
        ReconSetup s = new ReconSetup();
        s.setControls(new ArrayList<>(List.of(controls)));
        return s;
    }

    private static ControlDef control(String id, String gl, Side side, SourceDef... sources) {
        ControlDef c = new ControlDef();
        c.setId(id);
        c.setGlAccount(gl);
        c.setName(id);
        c.setNormal(side);
        c.setSources(new ArrayList<>(List.of(sources)));
        return c;
    }

    @Test
    void aBalancedTrialBalanceAndTiedControlsGiveNoFindings() {
        SheetData tb = tb(new Object[] {"1000", "Loans", d("150.00"), d("0")}, new Object[] {"2000", "Deposits", d("0"), d("150.00")},
                new Object[] {null, "TOTAL", d("150.00"), d("150.00")});
        SheetData loans = sheet("LOANS", new String[] {"Loan", "Principal"}, new Object[] {"L1", d("100")}, new Object[] {"L2", "50.00"});
        ReconResult r = new Reconciler(setup(control("loans", "1000", Side.DEBIT, new SourceDef("Loans", "principal", null, null, null))))
                .reconcile(List.of(tb, loans));
        assertTrue(r.findings().isEmpty(), () -> r.findings().toString());
        assertEquals(2, r.lines().size());
        assertEquals(d("150.00"), r.lines().get(0).left(), "the totals row (no account) is not counted twice");
        assertEquals(ReconLine.Status.TIES, r.lines().get(1).status());
    }

    @Test
    void anUnbalancedTrialBalanceIsOneCriticalFindingOnItsLastRow() {
        SheetData tb = tb(new Object[] {"1000", "Loans", d("1000.50"), d("0")},
                new Object[] {"2000", "Deposits", d("0"), "(10.00)"}, new Object[] {"3000", "Equity", d("0"), "1,000.00"});
        ReconResult r = new Reconciler(setup()).reconcile(List.of(tb));
        assertEquals(1, r.findings().size());
        Finding f = r.findings().get(0);
        assertEquals("TB_BALANCE", f.ruleType());
        assertEquals(4, f.row());
        assertEquals("C", f.column(), "debits are the larger side");
        assertEquals("Debits exceed credits by 10.50", f.message());
        assertEquals("10.50", f.value(), "the variance identifies the finding across checks");
    }

    @Test
    void toleranceAbsorbsRoundingDifferences() {
        SheetData tb = tb(new Object[] {"1000", "Loans", d("100.01"), d("0")}, new Object[] {"2000", "Deposits", d("0"), d("100.00")});
        ReconSetup s = setup();
        s.getTrialBalance().setTolerance(d("0.05"));
        assertTrue(new Reconciler(s).reconcile(List.of(tb)).findings().isEmpty());
    }

    @Test
    void controlVarianceSaysWhichWayAndByHowMuch() {
        SheetData tb = tb(new Object[] {"2000", "Deposits", d("0"), d("500")}, new Object[] {"1000", "Cash", d("500"), d("0")});
        SheetData savings = sheet("SAVINGS", new String[] {"Account No.", "Balance"},
                new Object[] {"10001", d("300")}, new Object[] {"10002", d("999")}, new Object[] {"20001", d("150")});
        ControlDef c = control("deposits", "2000", Side.CREDIT, new SourceDef("SAVINGS", "Balance", "Account No.", null, "01"));
        ReconResult r = new Reconciler(setup(c)).reconcile(List.of(tb, savings));
        assertEquals(1, r.findings().size());
        Finding f = r.findings().get(0);
        assertEquals("CONTROL", f.ruleType());
        assertEquals(2, f.row(), "on the GL account's row");
        assertEquals("D", f.column(), "credit-normal account: the Credit column");
        assertEquals("GL 2000 deposits is 50.00 above its subledger", f.message());
        ReconLine l = r.lines().get(1);
        assertEquals(d("500.00"), l.left());
        assertEquals(d("450.00"), l.right());
        assertEquals(d("-50.00"), l.variance());
    }

    @Test
    void aGlAccountMissingFromTheTrialBalanceIsAVarianceForTheWholeSubledger() {
        SheetData tb = tb(new Object[] {"1000", "Cash", d("10"), d("10")});
        SheetData loans = sheet("LOANS", new String[] {"Principal"}, new Object[] {d("70")});
        ReconResult r = new Reconciler(setup(control("loans", "1100", Side.DEBIT, new SourceDef("LOANS", "Principal", null, null, null))))
                .reconcile(List.of(tb, loans));
        assertEquals(1, r.findings().size());
        assertTrue(r.findings().get(0).message().contains("not in the trial balance"));
        assertEquals("GL 1100 is not in the trial balance", r.lines().get(1).note());
    }

    @Test
    void missingSheetsAreReportedAsNotChecked() {
        ControlDef c = control("loans", "1000", Side.DEBIT, new SourceDef("LOANS", "Principal", null, null, null));
        ReconResult none = new Reconciler(setup(c)).reconcile(List.of());
        assertEquals(2, none.skipped().size());
        assertTrue(none.lines().stream().allMatch(l -> l.status() == ReconLine.Status.NOT_CHECKED));
        assertTrue(none.findings().isEmpty(), "nothing to reconcile is not a variance");

        ReconResult noLoans = new Reconciler(setup(c)).reconcile(List.of(tb(new Object[] {"1000", "Loans", d("5"), d("5")})));
        assertEquals(1, noLoans.skipped().size());
        assertEquals(ReconLine.Status.NOT_CHECKED, noLoans.lines().get(1).status());
        assertNull(noLoans.lines().get(1).variance());
    }

    @Test
    void theShippedSaccoSetupHasNineControls() {
        ReconSetup s = ReconLoader.defaults();
        assertEquals(9, s.getControls().size());
        assertEquals("TRIAL BALANCE", s.getTrialBalance().getSheet());
    }
}
