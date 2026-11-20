package com.databridge.core.report;

import com.databridge.core.domain.Client;
import com.databridge.core.domain.MigrationRun;
import com.databridge.core.domain.Project;
import com.databridge.core.domain.StoredFinding;
import com.databridge.core.domain.ValidationCheck;
import com.databridge.core.recon.ReconLine;
import com.databridge.core.service.ReconciliationService;
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
import java.math.BigDecimal;
import java.util.List;

/**
 * The reconciliation pack: the trial balance totals and each control account against its
 * subledgers, with every variance's explanation and who approved it. Account totals only.
 */
public final class ReconciliationPack {

    private ReconciliationPack() { }

    public static byte[] write(Client client, Project project, MigrationRun run, ValidationCheck c,
                               List<ReconciliationService.Line> lines) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Font wf = wb.createFont();
            wf.setBold(true);
            wf.setColor(IndexedColors.WHITE.getIndex());
            CellStyle header = wb.createCellStyle();
            header.setFont(wf);
            header.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setWrapText(true);
            Font bf = wb.createFont();
            bf.setBold(true);
            CellStyle bold = wb.createCellStyle();
            bold.setFont(bf);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00;[Red](#,##0.00)"));
            Font rf = wb.createFont();
            rf.setBold(true);
            rf.setColor(IndexedColors.DARK_RED.getIndex());
            CellStyle bad = wb.createCellStyle();
            bad.setFont(rf);

            long variances = lines.stream().filter(l -> l.line().status() == ReconLine.Status.VARIANCE).count();
            long open = lines.stream().filter(l -> l.finding() != null && !l.finding().status().name().equals("WAIVED")).count();
            Sheet sum = wb.createSheet("Summary");
            String[][] head = {
                    {"Client", client.code() + " - " + client.name()},
                    {"Project", project.code() + " - " + project.name()},
                    {"Run", (run.type().name().equals("CUTOVER") ? "Cutover" : "Mock") + " run " + run.runNo() + ", cutoff " + run.cutoffDate()},
                    {"Check", c.checkNo() + " of " + c.createdAt() + " by " + c.createdBy()},
                    {"Lines", lines.size() + " (" + variances + " with a variance, " + open + " not yet approved)"},
                    {"Result", variances == 0 ? "Reconciled" : open == 0 ? "Reconciled with approved explanations" : "Not reconciled"},
            };
            int r = 0;
            for (String[] h : head) {
                Row row = sum.createRow(r++);
                row.createCell(0).setCellValue(h[0]);
                row.getCell(0).setCellStyle(bold);
                row.createCell(1).setCellValue(h[1]);
            }
            r++;
            sum.createRow(r).createCell(0).setCellValue("Account-level totals only; DataBridge keeps no record-level client data.");
            sum.setColumnWidth(0, 14 * 256);
            sum.setColumnWidth(1, 70 * 256);

            Sheet s = wb.createSheet("Reconciliation");
            String[] cols = {"Line", "Type", "GL account", "Name", "GL balance / Debits", "Subledger / Credits", "Variance", "Status",
                    "Subledgers", "Note", "Explanation", "Explained by", "Approval", "Approved by", "Approved at"};
            Row hr = s.createRow(0);
            for (int i = 0; i < cols.length; i++) {
                hr.createCell(i).setCellValue(cols[i]);
                hr.getCell(i).setCellStyle(header);
            }
            int q = 1;
            for (ReconciliationService.Line l : lines) {
                ReconLine x = l.line();
                StoredFinding f = l.finding();
                Row row = s.createRow(q++);
                row.createCell(0).setCellValue(x.lineNo());
                row.createCell(1).setCellValue(x.type() == ReconLine.Type.TB ? "Trial balance" : "Control account");
                text(row, 2, x.glAccount());
                text(row, 3, x.name());
                amount(row, 4, x.left(), money);
                amount(row, 5, x.right(), money);
                amount(row, 6, x.variance(), money);
                text(row, 7, x.status().name());
                if (x.status() == ReconLine.Status.VARIANCE) row.getCell(7).setCellStyle(bad);
                text(row, 8, x.sources());
                text(row, 9, x.note());
                if (f != null) {
                    text(row, 10, f.waiverReason());
                    text(row, 11, f.requestedBy());
                    text(row, 12, f.status().name().equals("WAIVED") ? "Approved"
                            : f.status().name().equals("WAIVER_REQUESTED") ? "Awaiting Finance" : "Not explained");
                    text(row, 13, f.status().name().equals("WAIVED") ? f.decidedBy() : null);
                    text(row, 14, f.status().name().equals("WAIVED") && f.decidedAt() != null ? f.decidedAt().toString() : null);
                }
            }
            int[] widths = {6, 16, 12, 34, 20, 20, 16, 13, 50, 36, 50, 16, 16, 16, 24};
            for (int i = 0; i < widths.length; i++) s.setColumnWidth(i, widths[i] * 256);
            s.createFreezePane(0, 1);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void text(Row row, int i, String v) {
        if (v != null) row.createCell(i).setCellValue(v);
    }

    private static void amount(Row row, int i, BigDecimal v, CellStyle style) {
        if (v == null) return;
        row.createCell(i).setCellValue(v.doubleValue());
        row.getCell(i).setCellStyle(style);
    }
}
