package com.databridge.core.service;

import com.databridge.core.domain.Client;
import com.databridge.core.domain.DatasetVersion;
import com.databridge.core.domain.MigrationRun;
import com.databridge.core.domain.Project;
import com.databridge.core.domain.RunType;
import com.databridge.core.domain.TemplateKind;
import com.databridge.core.run.RunStatus;
import com.databridge.core.setup.DataBridgeSetup;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.stream.Stream;

import static com.databridge.core.support.TestWorkbooks.caption;
import static com.databridge.core.support.TestWorkbooks.set;
import static com.databridge.core.support.TestWorkbooks.workbook;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared intake behaviour; runs against in-memory here and JDBC in the store module. */
public abstract class IntakeServiceContract {

    @TempDir protected Path tmp;

    protected abstract Fixture fixture(Path tmp);

    static byte[] membersWorkbook() throws Exception {
        XSSFWorkbook wb = workbook();
        Sheet s = wb.createSheet("Members");
        set(s, 0, 0, "STATIC DATA");
        set(s, 0, 1, "Members");
        set(s, 0, 2, 52185700);
        caption(s, 2, 0, "No.", "Code50");
        caption(s, 2, 1, "Name", "Text80");
        caption(s, 2, 2, "P.I.N Number", "Code20");
        set(s, 3, 0, "152009");
        set(s, 3, 1, "JOSEPH DOE DOE");
        set(s, 3, 2, "A1239940094Q");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        wb.close();
        return out.toByteArray();
    }

    private MigrationRun openRun(Fixture fx) {
        Client c = fx.migrations.createClient("AZIMA", "AZIMA DT SACCO", "harry");
        Project p = fx.migrations.createProject(c.id(), "BC", "Go-live", null, "harry");
        return fx.migrations.openRun(p.id(), RunType.MOCK, LocalDate.of(2027, 1, 31), "harry");
    }

    private static long workspaceCount(Fixture fx) throws Exception {
        if (!Files.isDirectory(fx.workspaceRoot)) return 0;
        try (Stream<Path> s = Files.list(fx.workspaceRoot)) {
            return s.count();
        }
    }

    @Test
    void uploadIsRecordedAsStructureOnlyAndTheFileIsDestroyed() throws Exception {
        Fixture fx = fixture(tmp);
        MigrationRun run = openRun(fx);
        byte[] bytes = membersWorkbook();

        IntakeResult r = fx.intake.intake(run.id(), TemplateKind.STATIC, "C:\\Azima\\Static Data Templates.xlsx",
                new ByteArrayInputStream(bytes), "harry");

        assertTrue(r.succeeded(), String.valueOf(r.dataset().error()));
        DatasetVersion d = fx.intake.dataset(r.dataset().id());
        assertEquals("Static Data Templates.xlsx", d.fileName());
        assertEquals(1, d.versionNo());
        assertEquals(1, d.sheetCount());
        assertEquals(bytes.length, d.sizeBytes());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), d.sha256());
        assertTrue(d.schemaJson().contains("52185700"));
        for (String clientValue : new String[] {"152009", "JOSEPH", "A1239940094Q"}) {
            assertFalse(d.schemaJson().contains(clientValue), "schema must not hold " + clientValue);
        }
        assertEquals(0, workspaceCount(fx), "workspace destroyed after intake");
        assertEquals("DATASET_UPLOADED", fx.audit.list("DATASET", d.id(), 1).get(0).action());
    }

    @Test
    void versionsCountUpPerKind() throws Exception {
        Fixture fx = fixture(tmp);
        MigrationRun run = openRun(fx);
        fx.intake.intake(run.id(), TemplateKind.STATIC, "a.xlsx", new ByteArrayInputStream(membersWorkbook()), "harry");
        IntakeResult v2 = fx.intake.intake(run.id(), TemplateKind.STATIC, "a.xlsx", new ByteArrayInputStream(membersWorkbook()), "harry");
        IntakeResult hr = fx.intake.intake(run.id(), TemplateKind.HR, "b.xlsx", new ByteArrayInputStream(membersWorkbook()), "harry");
        assertEquals(2, v2.dataset().versionNo());
        assertEquals(1, hr.dataset().versionNo());
        assertEquals(3, fx.intake.datasets(run.id()).size());
    }

    @Test
    void corruptWorkbookIsRecordedAsErrorNeverAsPass() throws Exception {
        Fixture fx = fixture(tmp);
        MigrationRun run = openRun(fx);
        IntakeResult r = fx.intake.intake(run.id(), TemplateKind.STATIC, "bad.xlsx",
                new ByteArrayInputStream(new byte[] {1, 2, 3}), "harry");
        assertFalse(r.succeeded());
        assertEquals(RunStatus.ERROR, fx.intake.dataset(r.dataset().id()).status());
        assertNull(r.schema());
        assertEquals(0, workspaceCount(fx));
    }

    @Test
    void oversizedUploadIsStoppedAndRecordedAsError() throws Exception {
        Fixture fx = fixture(tmp);
        DataBridgeSetup g = fx.setups.effective().general();
        g.setMaxUploadMb(1);
        fx.setups.updateGeneral(g, "harry");
        MigrationRun run = openRun(fx);
        InputStream twoMb = new ByteArrayInputStream(new byte[2 * 1024 * 1024]);

        IntakeResult r = fx.intake.intake(run.id(), TemplateKind.STATIC, "big.xlsx", twoMb, "harry");
        assertEquals(RunStatus.ERROR, r.dataset().status());
        assertTrue(r.dataset().error().contains("1 MB limit"), r.dataset().error());
        assertEquals(0, workspaceCount(fx));
    }

    @Test
    void closedRunRejectsUploads() throws Exception {
        Fixture fx = fixture(tmp);
        MigrationRun run = openRun(fx);
        fx.migrations.closeRun(run.id(), "harry");
        assertThrows(ValidationException.class, () -> fx.intake.intake(run.id(), TemplateKind.STATIC, "a.xlsx",
                new ByteArrayInputStream(membersWorkbook()), "harry"));
    }

    @Test
    void onlyWorkbooksAreAccepted() {
        Fixture fx = fixture(tmp);
        MigrationRun run = openRun(fx);
        assertThrows(ValidationException.class, () -> fx.intake.intake(run.id(), TemplateKind.STATIC, "members.csv",
                new ByteArrayInputStream(new byte[] {1}), "harry"));
    }

    @Test
    void purgeAllIsAudited() {
        Fixture fx = fixture(tmp);
        fx.retention.purgeAll("harry");
        assertEquals("WORKSPACES_PURGED", fx.audit.list("WORKSPACE", null, 1).get(0).action());
    }
}
