package com.databridge.core.report;

import com.databridge.core.domain.AuditEvent;
import com.databridge.core.domain.SignOff;
import com.databridge.core.service.ReportingService;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/** The run sign-off pack and the audit export, as .xlsx. Counts and decisions only; no client data. */
public final class SignOffPack {

    private SignOffPack() { }

    public static byte[] write(ReportingService.RunSummary s, List<ReportingService.CheckItem> checklist, List<AuditEvent> events) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            Sheet sum = wb.createSheet("Sign-off");
            SignOff so = s.signOff();
            String[][] head = {
                    {"Client", s.client().code() + " - " + s.client().name()},
                    {"Project", s.project().code() + " - " + s.project().name()},
                    {"Run", (s.run().type().name().equals("CUTOVER") ? "Cutover" : "Mock") + " run " + s.run().runNo() + ", cutoff " + s.run().cutoffDate()},
                    {"Run state", s.run().state().name()},
                    {"Sign-off", so == null ? "Not requested" : so.status().name()},
                    {"Certificate", so == null || so.certificateNo() == null ? "" : so.certificateNo()},
                    {"Requested by", so == null ? "" : so.requestedBy() + " at " + so.requestedAt()},
                    {"Decided by", so == null || so.decidedBy() == null ? "" : so.decidedBy() + " at " + so.decidedAt()},
                    {"Notes", so == null ? "" : join(so.requestNote(), so.decisionNote())},
            };
            int r = 0;
            for (String[] h : head) kv(sum, r++, h[0], h[1], st);
            r++;
            Row hr = sum.createRow(r++);
            String[] cols = {"Readiness item", "Passed", "Detail"};
            for (int i = 0; i < cols.length; i++) cell(hr, i, cols[i], st.header);
            for (ReportingService.CheckItem i : checklist) {
                Row row = sum.createRow(r++);
                cell(row, 0, i.label(), null);
                cell(row, 1, i.passed() ? "Yes" : "No", i.passed() ? st.good : st.bad);
                cell(row, 2, i.detail(), null);
            }
            r++;
            cell(sum.createRow(r), 0, "Counts and decisions only; DataBridge keeps no client records.", st.note);
            sum.setColumnWidth(0, 36 * 256);
            sum.setColumnWidth(1, 60 * 256);
            sum.setColumnWidth(2, 70 * 256);

            Sheet k = wb.createSheet("KPIs");
            int q = 0;
            ReportingService.FindingKpis f = s.findings();
            ReportingService.ReconKpis rc = s.reconciliation();
            Object[][] kpis = {
                    {"Checks run", s.trend().size()},
                    {"Latest readiness (rows without a Critical finding)", s.latestCheck() == null ? "" : s.latestCheck().readiness().movePointRight(2) + "%"},
                    {"Critical findings open", f.openCritical()},
                    {"Critical findings awaiting a waiver decision", f.awaitingCritical()},
                    {"Critical findings waived", f.waivedCritical()},
                    {"Warnings open", f.openWarning()},
                    {"Reconciliation lines tie", rc.ties()},
                    {"Reconciliation variances", rc.variances()},
                    {"Variances without an approved explanation", rc.unapproved()},
                    {"Workbooks cleaned", s.cleaning().jobs()},
                    {"Cells changed by cleaning", s.cleaning().cells()},
                    {"Import packages built", s.packages()},
                    {"Tables imported or matched", s.imports().done() + " of " + s.imports().tables()},
            };
            Row kh = k.createRow(q++);
            cell(kh, 0, "Measure", st.header);
            cell(kh, 1, "Value", st.header);
            for (Object[] x : kpis) {
                Row row = k.createRow(q++);
                cell(row, 0, String.valueOf(x[0]), null);
                if (x[1] instanceof Integer n) row.createCell(1).setCellValue(n);
                else cell(row, 1, String.valueOf(x[1]), null);
            }
            q++;
            Row th = k.createRow(q++);
            String[] tc = {"Check", "When", "Result", "Readiness", "Critical", "Warnings"};
            for (int i = 0; i < tc.length; i++) cell(th, i, tc[i], st.header);
            for (ReportingService.TrendPoint t : s.trend()) {
                Row row = k.createRow(q++);
                row.createCell(0).setCellValue(t.checkNo());
                cell(row, 1, t.at().toString(), null);
                cell(row, 2, t.status().name(), null);
                row.createCell(3).setCellValue(t.readiness().doubleValue());
                row.getCell(3).setCellStyle(st.pct);
                row.createCell(4).setCellValue(t.critical());
                row.createCell(5).setCellValue(t.warning());
            }
            k.setColumnWidth(0, 52 * 256);
            k.setColumnWidth(1, 28 * 256);
            for (int i = 2; i < 6; i++) k.setColumnWidth(i, 14 * 256);

            auditSheet(wb, "Run events", events, st);
            return bytes(wb);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] audit(List<AuditEvent> events) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            auditSheet(wb, "Audit trail", events, new Styles(wb));
            return bytes(wb);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void auditSheet(XSSFWorkbook wb, String name, List<AuditEvent> events, Styles st) {
        Sheet a = wb.createSheet(name);
        String[] cols = {"When (UTC)", "Who", "Action", "Entity", "Entity id", "Details"};
        Row hr = a.createRow(0);
        for (int i = 0; i < cols.length; i++) cell(hr, i, cols[i], st.header);
        int r = 1;
        for (AuditEvent e : events) {
            Row row = a.createRow(r++);
            cell(row, 0, e.occurredAt().toString(), null);
            cell(row, 1, e.actor(), null);
            cell(row, 2, e.action(), null);
            cell(row, 3, e.entityType(), null);
            cell(row, 4, e.entityId(), null);
            cell(row, 5, e.details(), null);
        }
        int[] w = {26, 18, 26, 14, 38, 90};
        for (int i = 0; i < w.length; i++) a.setColumnWidth(i, w[i] * 256);
        a.createFreezePane(0, 1);
    }

    private static String join(String a, String b) {
        if (a == null) return b == null ? "" : b;
        return b == null ? a : a + " / " + b;
    }

    private static void kv(Sheet s, int r, String k, String v, Styles st) {
        Row row = s.createRow(r);
        cell(row, 0, k, st.bold);
        cell(row, 1, v, null);
    }

    private static void cell(Row row, int i, String v, CellStyle style) {
        if (v == null) return;
        row.createCell(i).setCellValue(v);
        if (style != null) row.getCell(i).setCellStyle(style);
    }

    private static byte[] bytes(XSSFWorkbook wb) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        return out.toByteArray();
    }

    private static final class Styles {
        final CellStyle header, bold, good, bad, note, pct;

        Styles(XSSFWorkbook wb) {
            Font white = wb.createFont();
            white.setBold(true);
            white.setColor(IndexedColors.WHITE.getIndex());
            header = wb.createCellStyle();
            header.setFont(white);
            header.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font b = wb.createFont();
            b.setBold(true);
            bold = wb.createCellStyle();
            bold.setFont(b);
            Font g = wb.createFont();
            g.setBold(true);
            g.setColor(IndexedColors.GREEN.getIndex());
            good = wb.createCellStyle();
            good.setFont(g);
            Font rd = wb.createFont();
            rd.setBold(true);
            rd.setColor(IndexedColors.DARK_RED.getIndex());
            bad = wb.createCellStyle();
            bad.setFont(rd);
            Font i = wb.createFont();
            i.setItalic(true);
            i.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            note = wb.createCellStyle();
            note.setFont(i);
            pct = wb.createCellStyle();
            pct.setDataFormat(wb.createDataFormat().getFormat("0.0%"));
        }
    }
}
