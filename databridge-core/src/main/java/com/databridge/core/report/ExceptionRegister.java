package com.databridge.core.report;

import com.databridge.core.domain.Client;
import com.databridge.core.domain.FindingCount;
import com.databridge.core.domain.MigrationRun;
import com.databridge.core.domain.Project;
import com.databridge.core.domain.StoredFinding;
import com.databridge.core.domain.ValidationCheck;
import com.databridge.core.finding.FindingStatus;
import com.databridge.core.finding.Severity;
import com.databridge.core.validation.SheetStat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/** Writes a check's exception register as .xlsx. Holds masked values only, like the database. */
public final class ExceptionRegister {

    private ExceptionRegister() { }

    public static byte[] write(Client client, Project project, MigrationRun run, ValidationCheck c, List<FindingCount> counts,
                               List<StoredFinding> findings) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200)) {
            Styles st = new Styles(wb);

            Sheet sum = wb.createSheet("Summary");
            String[][] head = {
                    {"Client", client.code() + " - " + client.name()},
                    {"Project", project.code() + " - " + project.name()},
                    {"Run", (run.type().name().equals("CUTOVER") ? "Cutover" : "Mock") + " run " + run.runNo() + ", cutoff " + run.cutoffDate()},
                    {"Check", c.checkNo() + " of " + c.createdAt() + " by " + c.createdBy()},
                    {"Result", c.status().name()},
                    {"Rows checked", String.valueOf(c.rows())},
                    {"Readiness", c.readiness().movePointRight(2).stripTrailingZeros().toPlainString() + "% of rows without a Critical finding"},
            };
            int r = 0;
            for (String[] h : head) {
                Row row = sum.createRow(r++);
                cell(row, 0, h[0], st.bold);
                cell(row, 1, h[1], null);
            }
            r++;
            Row hr = sum.createRow(r++);
            String[] cols = {"Severity", "Open", "Waiver requested", "Waived", "Resolved"};
            for (int i = 0; i < cols.length; i++) cell(hr, i, cols[i], st.header);
            for (Severity s : Severity.values()) {
                Row row = sum.createRow(r++);
                cell(row, 0, s.name(), null);
                int i = 1;
                for (FindingStatus fs : FindingStatus.values()) {
                    int n = counts.stream().filter(x -> x.severity() == s && x.status() == fs).mapToInt(FindingCount::count).sum();
                    row.createCell(i++).setCellValue(n);
                }
            }
            r++;
            cell(sum.createRow(r), 0, "Values are masked; DataBridge does not keep client data.", st.note);
            sum.setColumnWidth(0, 22 * 256);
            sum.setColumnWidth(1, 60 * 256);
            for (int i = 2; i < 5; i++) sum.setColumnWidth(i, 18 * 256);

            Sheet qs = wb.createSheet("Sheet quality");
            String[] qcols = {"Workbook", "Sheet", "BC table ID", "Rows", "Rows passing", "Pass rate", "Critical", "Warnings"};
            Row qh = qs.createRow(0);
            for (int i = 0; i < qcols.length; i++) cell(qh, i, qcols[i], st.header);
            SheetStat[] stats = new ObjectMapper().readValue(c.sheetsJson() == null ? "[]" : c.sheetsJson(), SheetStat[].class);
            int q = 1;
            for (SheetStat s : stats) {
                Row row = qs.createRow(q++);
                cell(row, 0, s.kind(), null);
                cell(row, 1, s.sheet(), null);
                if (s.tableId() != null) row.createCell(2).setCellValue(s.tableId());
                row.createCell(3).setCellValue(s.rows());
                row.createCell(4).setCellValue(s.rows() - s.rowsWithCritical());
                row.createCell(5).setCellValue(s.passRate());
                row.getCell(5).setCellStyle(st.pct);
                row.createCell(6).setCellValue(s.critical());
                row.createCell(7).setCellValue(s.warning());
            }
            int[] qw = {16, 32, 12, 10, 12, 10, 10, 10};
            for (int i = 0; i < qw.length; i++) qs.setColumnWidth(i, qw[i] * 256);
            qs.createFreezePane(0, 1);

            SXSSFSheet fs = wb.createSheet("Findings");
            String[] fcols = {"Severity", "Status", "Workbook", "Sheet", "Row", "Column", "Field", "Problem", "Rule", "Value (masked)",
                    "Auto-fixable", "Waiver reason", "Requested by", "Decided by", "Decision note"};
            Row fh = fs.createRow(0);
            for (int i = 0; i < fcols.length; i++) cell(fh, i, fcols[i], st.header);
            int fr = 1;
            for (StoredFinding f : findings) {
                Row row = fs.createRow(fr++);
                cell(row, 0, f.severity().name(), f.severity() == Severity.CRITICAL ? st.critical : f.severity() == Severity.WARNING ? st.warning : null);
                cell(row, 1, f.status().name(), null);
                cell(row, 2, f.kind(), null);
                cell(row, 3, f.sheet(), null);
                if (f.rowNo() > 0) row.createCell(4).setCellValue(f.rowNo());
                cell(row, 5, f.columnRef(), null);
                cell(row, 6, f.field(), null);
                cell(row, 7, f.message(), null);
                cell(row, 8, f.ruleId(), null);
                cell(row, 9, f.value(), null);
                cell(row, 10, f.autoFixable() ? "Yes" : "No", null);
                cell(row, 11, f.waiverReason(), null);
                cell(row, 12, f.requestedBy(), null);
                cell(row, 13, f.decidedBy(), null);
                cell(row, 14, f.decisionNote(), null);
            }
            int[] fw = {10, 16, 14, 26, 7, 7, 24, 60, 30, 18, 11, 40, 18, 18, 30};
            for (int i = 0; i < fw.length; i++) fs.setColumnWidth(i, fw[i] * 256);
            fs.createFreezePane(0, 1);
            if (fr > 1) fs.setAutoFilter(new CellRangeAddress(0, fr - 1, 0, fcols.length - 1));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            wb.dispose();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void cell(Row row, int col, String v, CellStyle style) {
        if (v == null) return;
        org.apache.poi.ss.usermodel.Cell c = row.createCell(col);
        c.setCellValue(v);
        if (style != null) c.setCellStyle(style);
    }

    private static final class Styles {
        final CellStyle header, bold, pct, critical, warning, note;

        Styles(SXSSFWorkbook wb) {
            Font white = wb.createFont();
            white.setBold(true);
            white.setColor(IndexedColors.WHITE.getIndex());
            white.setFontName("Arial");
            header = wb.createCellStyle();
            header.setFont(white);
            header.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font b = wb.createFont();
            b.setBold(true);
            b.setFontName("Arial");
            bold = wb.createCellStyle();
            bold.setFont(b);
            pct = wb.createCellStyle();
            pct.setDataFormat(wb.createDataFormat().getFormat("0.0%"));
            Font red = wb.createFont();
            red.setColor(IndexedColors.DARK_RED.getIndex());
            red.setBold(true);
            critical = wb.createCellStyle();
            critical.setFont(red);
            Font amber = wb.createFont();
            amber.setColor(IndexedColors.BROWN.getIndex());
            warning = wb.createCellStyle();
            warning.setFont(amber);
            Font i = wb.createFont();
            i.setItalic(true);
            i.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            note = wb.createCellStyle();
            note.setFont(i);
        }
    }
}
