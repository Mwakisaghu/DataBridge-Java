package com.databridge.core.schema;

import com.databridge.core.setup.FieldSetup;
import com.databridge.core.setup.SetupLoader;
import com.databridge.core.setup.SheetSetup;
import com.databridge.core.setup.TemplateSetup;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.databridge.core.support.TestWorkbooks.caption;
import static com.databridge.core.support.TestWorkbooks.set;
import static com.databridge.core.support.TestWorkbooks.workbook;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaExtractorTest {

    private final SchemaExtractor defaults = new SchemaExtractor(SetupLoader.loadDefaults().templates(), 10);

    @Test
    void bannerNotesRowAndCaptionsLikeTheMembersSheet() {
        XSSFWorkbook wb = workbook();
        Sheet s = wb.createSheet("Members");
        set(s, 0, 0, "STATIC DATA");
        set(s, 0, 1, "Members");
        set(s, 0, 2, 52185700);
        set(s, 1, 1, "branch code");
        caption(s, 2, 0, "No.", "Code50");
        caption(s, 2, 1, "Status", "Option\n0:  \n1: Open\n2: New\n3: Active");
        caption(s, 2, 2, "Gender", "Option\n0:  \n1: Male\n2: Female\n3: Others");
        set(s, 3, 0, "152009");
        set(s, 3, 1, "Active");

        TableSchema t = defaults.extract(wb, "test.xlsx").sheets().get(0);

        assertEquals("STATIC DATA", t.packageCode());
        assertEquals(52185700, t.tableId());
        assertEquals(3, t.captionRow());
        assertEquals(4, t.dataStartRow());
        assertEquals(3, t.fields().size());
        assertEquals(FieldType.CODE, t.fields().get(0).type());
        assertEquals(50, t.fields().get(0).length());
        assertEquals(4, t.fields().get(1).options().size());
        assertEquals(FieldSource.TEMPLATE_COMMENT, t.fields().get(1).source());
        assertTrue(t.warnings().isEmpty(), t.warnings().toString());
    }

    @Test
    void captionsInFirstRowWithoutBanner() {
        XSSFWorkbook wb = workbook();
        Sheet s = wb.createSheet("Salary Lines");
        caption(s, 0, 0, "Salary Header No.", "Code50");
        caption(s, 0, 1, "Amount", "Decimal");
        set(s, 1, 0, "SAL000002");

        TableSchema t = defaults.extract(wb, "x").sheets().get(0);
        assertEquals(1, t.captionRow());
        assertNull(t.tableId());
        assertTrue(t.warnings().stream().anyMatch(w -> w.contains("No BC table ID")));
    }

    @Test
    void sheetWithoutCommentsIsTypedFromTemplateSetup() {
        XSSFWorkbook wb = workbook();
        Sheet s = wb.createSheet("TRIAL BALANCE");
        String[] caps = {"No.", "Description", "Debit", "Credit", "Branch ", "Activity"};
        for (int i = 0; i < caps.length; i++) set(s, 0, i, caps[i]);

        TableSchema t = defaults.extract(wb, "x").sheets().get(0);
        assertEquals(6, t.typedFieldCount());
        assertTrue(t.fields().stream().allMatch(f -> f.source() == FieldSource.TEMPLATE_SETUP));
        assertTrue(t.warnings().isEmpty(), t.warnings().toString());
    }

    @Test
    void templateSetupWinsOverComment() {
        TemplateSetup setup = new TemplateSetup();
        SheetSetup ss = new SheetSetup();
        ss.setSheetName("Vendor");
        ss.setTableId(23);
        FieldSetup f = new FieldSetup();
        f.setCaption("No.");
        f.setType("Code20");
        ss.setFields(List.of(f));
        setup.put(ss);

        XSSFWorkbook wb = workbook();
        Sheet s = wb.createSheet("Vendor");
        caption(s, 0, 0, "No.", "Text50");

        FieldDef no = new SchemaExtractor(setup, 10).extract(wb, "x").sheets().get(0).fields().get(0);
        assertEquals(FieldType.CODE, no.type());
        assertEquals(20, no.length());
        assertEquals(FieldSource.TEMPLATE_SETUP, no.source());
    }

    @Test
    void missingColumnExpectedBySetupIsReported() {
        XSSFWorkbook wb = workbook();
        Sheet s = wb.createSheet("TRIAL BALANCE");
        String[] caps = {"No.", "Description", "Debit", "Branch", "Activity"};
        for (int i = 0; i < caps.length; i++) set(s, 0, i, caps[i]);

        TableSchema t = defaults.extract(wb, "x").sheets().get(0);
        assertTrue(t.warnings().stream().anyMatch(w -> w.contains("not found in the sheet") && w.contains("Credit")),
                t.warnings().toString());
    }

    @Test
    void duplicateCaptionIsReported() {
        XSSFWorkbook wb = workbook();
        Sheet s = wb.createSheet("Delegates");
        caption(s, 0, 0, "Code", "Code50");
        caption(s, 0, 1, "Name", "Text100");
        caption(s, 0, 2, "code ", "Code50");

        TableSchema t = defaults.extract(wb, "x").sheets().get(0);
        assertTrue(t.warnings().stream().anyMatch(w -> w.contains("Duplicate caption") && w.contains("A and C")));
    }

    @Test
    void setupCaptionRowOverridesDetection() {
        TemplateSetup setup = new TemplateSetup();
        SheetSetup ss = new SheetSetup();
        ss.setSheetName("Odd");
        ss.setCaptionRow(4);
        ss.setTableId(99);
        setup.put(ss);

        XSSFWorkbook wb = workbook();
        Sheet s = wb.createSheet("Odd");
        set(s, 0, 0, "Some");
        set(s, 0, 1, "Title");
        set(s, 3, 0, "Real");
        set(s, 3, 1, "Captions");

        TableSchema t = new SchemaExtractor(setup, 10).extract(wb, "x").sheets().get(0);
        assertEquals(4, t.captionRow());
        assertEquals("Real", t.fields().get(0).caption());
    }

    @Test
    void emptySheetIsReportedNotSkipped() {
        XSSFWorkbook wb = workbook();
        wb.createSheet("Blank");
        TableSchema t = defaults.extract(wb, "x").sheets().get(0);
        assertTrue(t.fields().isEmpty());
        assertTrue(t.warnings().stream().anyMatch(w -> w.contains("No caption row")));
    }
}
