package com.databridge.core.service;

import com.databridge.core.domain.Client;
import com.databridge.core.domain.FindingFilter;
import com.databridge.core.domain.ImportRecord;
import com.databridge.core.domain.MigrationRun;
import com.databridge.core.domain.Project;
import com.databridge.core.domain.RunState;
import com.databridge.core.domain.RunType;
import com.databridge.core.domain.SignOff;
import com.databridge.core.domain.StoredFinding;
import com.databridge.core.domain.TemplateKind;
import com.databridge.core.finding.FindingStatus;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A whole run on the sample data, from first submission to signed-off certificate. */
class ReportingServiceTest {

    @TempDir Path tmp;
    private Fixture fx;
    private MigrationRun run;

    @BeforeEach
    void setUp() {
        fx = Fixture.inMemory(tmp);
        Client c = fx.migrations.createClient("SAMPLE", "SAMPLE SACCO", "harry");
        Project p = fx.migrations.createProject(c.id(), "BC", "Go-live", null, "harry");
        run = fx.migrations.openRun(p.id(), RunType.CUTOVER, LocalDate.of(2026, 10, 31), "harry");
    }

    private Path sample(String name) {
        return Samples.dir().resolve("clean").resolve("Sample " + name + " Data Templates.xlsx");
    }

    private String submitClean() throws IOException {
        Submission.View s = fx.intake.openSubmission(run.id(), "harry");
        for (String[] f : new String[][] {{"Static", "STATIC"}, {"HR", "HR"}, {"Transactional", "TRANSACTIONAL"}}) {
            try (InputStream in = Files.newInputStream(sample(f[0]))) {
                fx.intake.addFile(s.id(), TemplateKind.valueOf(f[1]), "Sample " + f[0] + ".xlsx", in, "harry");
            }
        }
        return fx.intake.process(s.id(), "harry").check().id();
    }

    private void packageAndImport(String name, TemplateKind kind) throws IOException {
        try (InputStream in = Files.newInputStream(sample(name))) {
            fx.integration.buildPackage(run.id(), kind, "Sample " + name + ".xlsx", in, "harry");
        }
    }

    private static ReportingService.CheckItem item(ReportingService.RunSummary s, String id) {
        return s.checklist().stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void aRunGoesFromFirstSubmissionToASignedOffCertificate() throws Exception {
        ReportingService.RunSummary empty = fx.reporting.summary(run.id());
        assertFalse(empty.ready());
        assertEquals("No check has been run", item(empty, "validated").detail());

        String checkId = submitClean();
        ReportingService.RunSummary afterCheck = fx.reporting.summary(run.id());
        assertEquals(48, afterCheck.findings().openCritical());
        assertFalse(item(afterCheck, "validated").passed());
        assertTrue(item(afterCheck, "reconciled").passed(), "the clean set ties: " + item(afterCheck, "reconciled").detail());
        assertEquals(10, afterCheck.reconciliation().ties());
        ValidationException notReady = assertThrows(ValidationException.class, () -> fx.reporting.requestSignOff(run.id(), null, "harry"));
        assertTrue(notReady.getMessage().contains("Workbooks validated"), notReady.getMessage());

        // The 48 Criticals are the template's Integer phone columns: waived with maker-checker.
        for (StoredFinding f : fx.findings.page(checkId, FindingFilter.all(), 0, 500).items()) {
            fx.findings.requestWaiver(f.id(), "Template types phone as Integer; BC field is Text30", "harry");
            fx.findings.decideWaiver(f.id(), true, "Agreed", "rita");
        }
        assertTrue(item(fx.reporting.summary(run.id()), "validated").passed());

        packageAndImport("Static", TemplateKind.STATIC);
        packageAndImport("HR", TemplateKind.HR);
        ReportingService.RunSummary packaged = fx.reporting.summary(run.id());
        assertTrue(item(packaged, "packaged").passed());
        assertFalse(item(packaged, "imported").passed());
        for (ImportRecord r : fx.integration.imports(run.id())) {
            fx.integration.recordImport(run.id(), r.sheet(), r.expectedRows(), false, null, "harry");
        }
        ReportingService.RunSummary ready = fx.reporting.summary(run.id());
        assertTrue(ready.ready(), () -> ready.checklist().toString());
        assertEquals(48, ready.findings().waivedCritical());
        assertEquals(1, ready.trend().size());

        SignOff req = fx.reporting.requestSignOff(run.id(), "Cutover data complete", "harry");
        assertEquals(SignOff.Status.REQUESTED, req.status());
        assertThrows(ValidationException.class, () -> fx.reporting.requestSignOff(run.id(), null, "harry"), "already requested");
        assertThrows(ValidationException.class, () -> fx.reporting.decideSignOff(run.id(), true, null, "HARRY"), "maker-checker");
        assertThrows(ValidationException.class, () -> fx.reporting.decideSignOff(run.id(), false, " ", "fiona"), "a rejection needs a reason");

        SignOff ok = fx.reporting.decideSignOff(run.id(), true, "Balances agreed with the GL", "fiona");
        assertEquals(SignOff.Status.APPROVED, ok.status());
        assertEquals("DB-SAMPLE-BC-R1-20270131", ok.certificateNo());
        assertEquals(RunState.CLOSED, fx.migrations.run(run.id()).state(), "sign-off closes the run");
        assertTrue(fx.auditRepo.list("RUN", run.id(), 50).stream().anyMatch(a -> a.action().equals("SIGNOFF_APPROVED")));

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(fx.reporting.signOffPack(run.id())))) {
            assertEquals("DB-SAMPLE-BC-R1-20270131", wb.getSheet("Sign-off").getRow(5).getCell(1).getStringCellValue());
            assertEquals(List.of("Sign-off", "KPIs", "Run events"), List.of(wb.getSheetName(0), wb.getSheetName(1), wb.getSheetName(2)));
        }
    }

    @Test
    void approvalRechecksReadinessAndRejectionAllowsANewRequest() throws Exception {
        String checkId = submitClean();
        for (StoredFinding f : fx.findings.page(checkId, FindingFilter.all(), 0, 500).items()) {
            fx.findings.requestWaiver(f.id(), "Template types phone as Integer; BC field is Text30", "harry");
            fx.findings.decideWaiver(f.id(), true, null, "rita");
        }
        packageAndImport("Static", TemplateKind.STATIC);
        packageAndImport("HR", TemplateKind.HR);
        for (ImportRecord r : fx.integration.imports(run.id())) fx.integration.recordImport(run.id(), r.sheet(), r.expectedRows(), false, null, "harry");
        fx.reporting.requestSignOff(run.id(), null, "harry");

        SignOff rejected = fx.reporting.decideSignOff(run.id(), false, "Members count to be confirmed in BC first", "fiona");
        assertEquals(SignOff.Status.REJECTED, rejected.status());
        assertNull(rejected.certificateNo());
        assertEquals(RunState.OPEN, fx.migrations.run(run.id()).state());

        fx.reporting.requestSignOff(run.id(), "Confirmed", "harry");
        // Something changes after the request: a table is now recorded as failed.
        fx.integration.recordImport(run.id(), "Members", null, true, "Re-import needed", "harry");
        ValidationException changed = assertThrows(ValidationException.class,
                () -> fx.reporting.decideSignOff(run.id(), true, null, "fiona"));
        assertTrue(changed.getMessage().contains("no longer ready"));
        assertEquals(FindingStatus.WAIVED, fx.findings.page(checkId, FindingFilter.all(), 0, 1).items().get(0).status());
    }
}
