package com.databridge.core.cleaning;

import com.databridge.core.schema.FieldDef;
import com.databridge.core.schema.FieldSource;
import com.databridge.core.schema.FieldType;
import com.databridge.core.schema.OptionValue;
import com.databridge.core.schema.TableSchema;
import com.databridge.core.schema.WorkbookSchema;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CleanerTest {

    private static final List<OptionValue> STATUS = List.of(new OptionValue(0, "Active"), new OptionValue(1, "Dormant"),
            new OptionValue(2, "Deceased"), new OptionValue(3, "Amortised"), new OptionValue(4, "Amortized"));

    private static FieldDef field(int i, String caption, FieldType t, List<OptionValue> o) {
        return new FieldDef(i, String.valueOf((char) ('A' + i)), caption, t, null, o, false, FieldSource.TEMPLATE_COMMENT);
    }

    private static final List<FieldDef> FIELDS = List.of(field(0, "No.", FieldType.CODE, null), field(1, "Phone No.", FieldType.TEXT, null),
            field(2, "Status", FieldType.OPTION, STATUS), field(3, "Name", FieldType.TEXT, null), field(4, "Total", FieldType.DECIMAL, null));

    private static WorkbookSchema schema() {
        return new WorkbookSchema("t.xlsx", List.of(new TableSchema("Members", null, "Member", 1, null, 1, 2, FIELDS, List.of())));
    }

    /** Header in row 1, then the given rows. */
    private static XSSFWorkbook book(Object[]... rows) {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet s = wb.createSheet("Members");
        Row h = s.createRow(0);
        for (FieldDef f : FIELDS) h.createCell(f.columnIndex()).setCellValue(f.caption());
        for (int r = 0; r < rows.length; r++) {
            Row row = s.createRow(r + 1);
            for (int c = 0; c < rows[r].length; c++) {
                Object v = rows[r][c];
                if (v == null) continue;
                Cell cell = row.createCell(c);
                if (v instanceof Number n) cell.setCellValue(n.doubleValue());
                else if (v instanceof String str && str.startsWith("=")) cell.setCellFormula(str.substring(1));
                else cell.setCellValue(v.toString());
            }
        }
        return wb;
    }

    private static CleanRule rule(String id, String sheet, String field, CleanAction a) {
        CleanRule r = new CleanRule();
        r.setId(id);
        r.setSheet(sheet);
        r.setField(field);
        r.setAction(a);
        return r;
    }

    private static String text(XSSFWorkbook wb, int row, int col) {
        Cell c = wb.getSheet("Members").getRow(row).getCell(col);
        return c == null ? null : c.getCellType() == CellType.NUMERIC ? String.valueOf(c.getNumericCellValue()) : c.getStringCellValue();
    }

    @Test
    void kenyanPhoneNumbersAreNormalisedOnlyWhenCertain() {
        assertEquals("+254712345678", Cleaner.phone("0712 345 678"));
        assertEquals("+254712345678", Cleaner.phone("254712345678"));
        assertEquals("+254712345678", Cleaner.phone("712345678"));
        assertEquals("+254110000001", Cleaner.phone("(0110) 000-001"));
        assertEquals("+254712345678", Cleaner.phone("+254 712 345 678"));
        assertEquals("0201234567", Cleaner.phone("0201234567"), "a landline is left alone");
        assertEquals("12345", Cleaner.phone("12345"));
        assertEquals("call me", Cleaner.phone("call me"));
    }

    @Test
    void phoneStoredAsANumberBecomesText() {
        XSSFWorkbook wb = book(new Object[] {"M1", 712345678});
        CleanResult r = new Cleaner(List.of(rule("p", "Members", "Phone No.", CleanAction.PHONE_KE))).clean(wb, schema());
        assertEquals("+254712345678", text(wb, 1, 1));
        assertEquals(1, r.cells());
        assertEquals("2", r.changes().get(0).rows());
    }

    @Test
    void optionsAreFixedOnlyWhenTheIntendedValueIsCertain() {
        XSSFWorkbook wb = book(new Object[] {"M1", null, "ACTIVE"}, new Object[] {"M2", null, " dormant "},
                new Object[] {"M3", null, "Amortisd"}, new Object[] {"M4", null, "Gone"});
        new Cleaner(List.of(rule("o", "*", "*", CleanAction.OPTION_MATCH))).clean(wb, schema());
        assertEquals("Active", text(wb, 1, 2));
        assertEquals("Dormant", text(wb, 2, 2));
        assertEquals("Amortisd", text(wb, 3, 2), "two options are that close: no guess");
        assertEquals("Gone", text(wb, 4, 2));
    }

    @Test
    void trimKeepsTheCellStyleAndNeverTouchesFormulas() {
        XSSFWorkbook wb = book(new Object[] {"M1", null, null, "  JANE   DOE ", "=1+1"});
        CellStyle bold = wb.createCellStyle();
        bold.setFont(wb.createFont());
        wb.getSheet("Members").getRow(1).getCell(3).setCellStyle(bold);
        CleanResult r = new Cleaner(List.of(rule("t", "*", "*", CleanAction.TRIM), rule("u", "Members", "Total", CleanAction.UPPER)))
                .clean(wb, schema());
        Cell name = wb.getSheet("Members").getRow(1).getCell(3);
        assertEquals("JANE DOE", name.getStringCellValue());
        assertEquals(bold.getIndex(), name.getCellStyle().getIndex());
        assertEquals(CellType.FORMULA, wb.getSheet("Members").getRow(1).getCell(4).getCellType());
        assertEquals(1, r.cells());
    }

    @Test
    void padReplaceAndDefault() {
        XSSFWorkbook wb = book(new Object[] {"42", "x", null, "A-B"}, new Object[] {"7", "x", null, null});
        CleanRule pad = rule("pad", "Members", "No.", CleanAction.PAD_LEFT);
        pad.setLength(6);
        CleanRule rep = rule("rep", "Members", "Name", CleanAction.REPLACE);
        rep.setPattern("-");
        rep.setReplacement(" ");
        CleanRule def = rule("def", "Members", "Status", CleanAction.DEFAULT_VALUE);
        def.setValue("Active");
        new Cleaner(List.of(pad, rep, def)).clean(wb, schema());
        assertEquals("000042", text(wb, 1, 0));
        assertEquals("000007", text(wb, 2, 0));
        assertEquals("A B", text(wb, 1, 3));
        assertEquals("Active", text(wb, 1, 2));
        assertEquals("Active", text(wb, 2, 2));
    }

    @Test
    void onlyRowsIdenticalInEveryColumnAreEmptied() {
        XSSFWorkbook wb = book(new Object[] {"A1", "x", "Active", "Jane"}, new Object[] {"A1", "x", "Active", "Jane"},
                new Object[] {"A1", "x", "Active", "Janet"});
        CleanResult r = new Cleaner(List.of(rule("d", "Members", "*", CleanAction.REMOVE_DUPLICATE_ROWS))).clean(wb, schema());
        assertEquals(1, r.rowsEmptied());
        assertEquals("3", r.changes().get(0).rows());
        assertEquals(CellType.BLANK, wb.getSheet("Members").getRow(2).getCell(0).getCellType());
        assertEquals("Janet", text(wb, 3, 3), "same key, different row: kept for a person to decide");
    }

    @Test
    void theChangeLogHoldsNoValues() {
        XSSFWorkbook wb = book(new Object[] {"M1", "0712345678", "ACTIVE", " Secret Name "});
        CleanResult r = new Cleaner(CleanLoader.defaults()).clean(wb, schema());
        String log = r.changes().toString();
        assertFalse(log.contains("0712345678") || log.contains("Secret") || log.contains("ACTIVE"), log);
        assertTrue(r.cells() >= 3);
    }
}
