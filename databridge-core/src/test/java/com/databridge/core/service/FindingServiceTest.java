package com.databridge.core.service;

import com.databridge.core.domain.Client;
import com.databridge.core.domain.FindingFilter;
import com.databridge.core.domain.MigrationRun;
import com.databridge.core.domain.Project;
import com.databridge.core.domain.RunType;
import com.databridge.core.domain.StoredFinding;
import com.databridge.core.domain.TemplateKind;
import com.databridge.core.finding.FindingStatus;
import com.databridge.core.finding.Severity;
import com.databridge.core.validation.RuleDef;
import com.databridge.core.validation.RuleType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static com.databridge.core.support.TestWorkbooks.caption;
import static com.databridge.core.support.TestWorkbooks.set;
import static com.databridge.core.support.TestWorkbooks.workbook;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindingServiceTest {

    @TempDir Path tmp;

    /** Members with one bad gender (Critical) and one bad KRA PIN (Critical). */
    static byte[] members() throws Exception {
        XSSFWorkbook wb = workbook();
        Sheet s = wb.createSheet("Members");
        set(s, 0, 0, "STATIC DATA");
        set(s, 0, 1, "Members");
        set(s, 0, 2, 52185700);
        caption(s, 2, 0, "No.", "Code50");
        caption(s, 2, 1, "Name", "Text80");
        caption(s, 2, 2, "Gender", "Option\n0:  \n1: Male\n2: Female");
        caption(s, 2, 3, "P.I.N Number", "Code20");
        set(s, 3, 0, "200001"); set(s, 3, 1, "JANE AKINYI"); set(s, 3, 2, "Female"); set(s, 3, 3, "A123456789B");
        set(s, 4, 0, "200002"); set(s, 4, 1, "JOHN OTIENO"); set(s, 4, 2, "M"); set(s, 4, 3, "A123456789B");
        set(s, 5, 0, "200003"); set(s, 5, 1, "MARY WEKESA"); set(s, 5, 2, "Female"); set(s, 5, 3, "A12345678");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        wb.close();
        return out.toByteArray();
    }

    private record Ctx(Fixture fx, MigrationRun run) { }

    private Ctx ctx() {
        Fixture fx = Fixture.inMemory(tmp);
        Client c = fx.migrations.createClient("AZIMA", "AZIMA DT SACCO", "harry");
        Project p = fx.migrations.createProject(c.id(), "BC", "Go-live", null, "harry");
        return new Ctx(fx, fx.migrations.openRun(p.id(), RunType.MOCK, LocalDate.of(2026, 10, 31), "harry"));
    }

    private SubmissionResult upload(Ctx c) throws Exception {
        return c.fx.intake.intakeChecked(c.run.id(), TemplateKind.STATIC, "Static.xlsx", new ByteArrayInputStream(members()), "harry");
    }

    @Test
    void singleUploadIsValidatedAndRecordedAsACheck() throws Exception {
        Ctx c = ctx();
        SubmissionResult r = upload(c);
        assertEquals(1, r.check().checkNo());
        assertEquals(2, r.check().critical());
        assertEquals(3, r.check().rows());
        assertEquals(0.3333, r.check().readiness().doubleValue(), 1e-4);
        List<StoredFinding> f = c.fx.findings.page(r.check().id(), FindingFilter.all(), 0, 100).items();
        assertEquals(List.of("OPTION", "FORMAT"), f.stream().map(StoredFinding::ruleType).toList());
        assertEquals(1, c.fx.findings.page(r.check().id(), new FindingFilter(null, null, null, "FORMAT"), 0, 100).total());
    }

    @Test
    void waiverIsMakerChecker() throws Exception {
        Ctx c = ctx();
        String id = c.fx.findings.page(upload(c).check().id(), FindingFilter.all(), 0, 1).items().get(0).id();

        assertThrows(ValidationException.class, () -> c.fx.findings.requestWaiver(id, "short", "harry"));
        StoredFinding req = c.fx.findings.requestWaiver(id, "Member confirmed by HR; corrected after go-live", "harry");
        assertEquals(FindingStatus.WAIVER_REQUESTED, req.status());
        assertThrows(ValidationException.class, () -> c.fx.findings.decideWaiver(id, true, null, "HARRY"),
                "the maker cannot approve their own waiver");
        assertThrows(ValidationException.class, () -> c.fx.findings.decideWaiver(id, false, " ", "reviewer"),
                "a rejection needs a reason");
        StoredFinding ok = c.fx.findings.decideWaiver(id, true, "Agreed", "reviewer");
        assertEquals(FindingStatus.WAIVED, ok.status());
        assertEquals("reviewer", ok.decidedBy());
        assertThrows(ValidationException.class, () -> c.fx.findings.requestWaiver(id, "again and again", "harry"));
        assertEquals("WAIVER_APPROVED", c.fx.audit.list("FINDING", id, 1).get(0).action());
    }

    @Test
    void waiverIsCarriedToTheSameFindingInTheNextCheck() throws Exception {
        Ctx c = ctx();
        String first = upload(c).check().id();
        StoredFinding f = c.fx.findings.page(first, FindingFilter.all(), 0, 1).items().get(0);
        c.fx.findings.requestWaiver(f.id(), "Accepted by the client in writing", "harry");
        c.fx.findings.decideWaiver(f.id(), true, null, "reviewer");

        SubmissionResult second = upload(c);
        assertEquals(2, second.check().checkNo());
        List<StoredFinding> now = c.fx.findings.page(second.check().id(), FindingFilter.all(), 0, 100).items();
        StoredFinding carried = now.stream().filter(x -> x.matchKey().equals(f.matchKey())).findFirst().orElseThrow();
        assertEquals(FindingStatus.WAIVED, carried.status());
        assertTrue(carried.decisionNote().contains("check 1"));
        assertEquals(1, now.stream().filter(x -> x.status() == FindingStatus.OPEN).count());
    }

    @Test
    void registerIsAnExcelFileWithMaskedValuesOnly() throws Exception {
        Ctx c = ctx();
        byte[] xlsx = c.fx.findings.register(upload(c).check().id());
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertEquals(List.of("Summary", "Sheet quality", "Findings"), List.of(wb.getSheetName(0), wb.getSheetName(1), wb.getSheetName(2)));
            Sheet f = wb.getSheet("Findings");
            assertEquals(2, f.getLastRowNum());
            StringBuilder all = new StringBuilder();
            f.forEach(r -> r.forEach(cell -> all.append(cell.toString()).append('|')));
            assertFalse(all.toString().contains("A12345678|"), "raw value never appears");
            assertTrue(all.toString().contains("A1*****78"));
            assertEquals("AZIMA - AZIMA DT SACCO", wb.getSheet("Summary").getRow(0).getCell(1).getStringCellValue());
        }
    }

    @Test
    void submissionKeepsOneFileOfEachKindAndCanBeCancelled() throws Exception {
        Ctx c = ctx();
        Submission.View s = c.fx.intake.openSubmission(c.run.id(), "harry");
        c.fx.intake.addFile(s.id(), TemplateKind.STATIC, "a.xlsx", new ByteArrayInputStream(members()), "harry");
        c.fx.intake.addFile(s.id(), TemplateKind.STATIC, "b.xlsx", new ByteArrayInputStream(members()), "harry");
        assertEquals(1, c.fx.intake.submission(s.id()).files().size());
        assertEquals("b.xlsx", c.fx.intake.submission(s.id()).files().get(0).fileName());
        c.fx.intake.cancel(s.id(), "harry");
        assertThrows(NotFoundException.class, () -> c.fx.intake.submission(s.id()));
        assertThrows(ValidationException.class, () -> {
            Submission.View e = c.fx.intake.openSubmission(c.run.id(), "harry");
            c.fx.intake.process(e.id(), "harry");
        }, "an empty submission cannot be processed");
    }

    @Test
    void storedRuleOverridesAndSwitchesOffADefault() throws Exception {
        Ctx c = ctx();
        RuleDef off = c.fx.rules.effective().stream().filter(r -> r.getId().equals("members.kra-pin")).findFirst().orElseThrow();
        off.setEnabled(false);
        c.fx.rules.put(off, "harry");
        assertEquals(1, upload(c).check().critical(), "KRA PIN rule switched off");
        assertTrue(c.fx.rules.views().stream().anyMatch(v -> v.rule().getId().equals("members.kra-pin") && v.shipped() && v.stored()));
        assertTrue(c.fx.rules.delete("members.kra-pin", "harry"));
        assertEquals(2, upload(c).check().critical(), "default applies again");

        RuleDef bad = new RuleDef();
        bad.setId("bad rule");
        assertThrows(ValidationException.class, () -> c.fx.rules.put(bad, "harry"));
        RuleDef regex = new RuleDef();
        regex.setId("members.name-caps");
        regex.setType(RuleType.FORMAT);
        regex.setSheet("Members");
        regex.setField("Name");
        regex.setSeverity(Severity.INFO);
        regex.setPattern("([A-Z");
        assertThrows(ValidationException.class, () -> c.fx.rules.put(regex, "harry"), "regex must compile");
        regex.setPattern("^[A-Z .'-]+$");
        c.fx.rules.put(regex, "harry");
        assertEquals("RULE_ADDED", c.fx.audit.list("RULE", "members.name-caps", 1).get(0).action());
    }
}
