package com.databridge.core.service;

import com.databridge.core.domain.AuditEvent;
import com.databridge.core.setup.DataBridgeSetup;
import com.databridge.core.setup.FieldSetup;
import com.databridge.core.setup.RetentionMode;
import com.databridge.core.setup.SheetSetup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetupServiceTest {

    @TempDir Path tmp;

    private static SheetSetup sheet(String name, Integer tableId, String caption, String type) {
        SheetSetup s = new SheetSetup();
        s.setSheetName(name);
        s.setTableId(tableId);
        if (caption != null) {
            FieldSetup f = new FieldSetup();
            f.setCaption(caption);
            f.setType(type);
            s.setFields(List.of(f));
        }
        return s;
    }

    @Test
    void changeIsValidatedAndAuditedWithWhatChanged() {
        Fixture fx = Fixture.inMemory(tmp);
        DataBridgeSetup g = fx.setups.effective().general();
        g.getRetention().setMode(RetentionMode.KEEP_FOR_HOURS);
        g.setMaxUploadMb(300);
        fx.setups.updateGeneral(g, "harry");

        assertEquals(RetentionMode.KEEP_FOR_HOURS, fx.setups.effective().general().getRetention().getMode());
        AuditEvent e = fx.audit.list("SETUP", null, 1).get(0);
        assertEquals("harry", e.actor());
        assertTrue(e.details().contains("retention.mode: PURGE_ON_COMPLETE -> KEEP_FOR_HOURS"), e.details());
        assertTrue(e.details().contains("maxUploadMb: 200 -> 300"), e.details());
    }

    @Test
    void invalidGeneralSetupIsRejected() {
        Fixture fx = Fixture.inMemory(tmp);
        DataBridgeSetup g = fx.setups.effective().general();
        g.setCaptionScanRows(0);
        assertThrows(ValidationException.class, () -> fx.setups.updateGeneral(g, "harry"));
    }

    @Test
    void storedSheetOverridesShippedDefaultAndDeleteRestoresIt() {
        Fixture fx = Fixture.inMemory(tmp);
        assertEquals(15, fx.setups.effective().templates().find("Chart of Account").orElseThrow().getTableId());

        fx.setups.putSheet(sheet("chart of account", 9999, null, null), "harry");
        assertEquals(9999, fx.setups.effective().templates().find("Chart of Account").orElseThrow().getTableId());

        assertTrue(fx.setups.deleteSheet("CHART OF ACCOUNT", "harry"));
        assertEquals(15, fx.setups.effective().templates().find("Chart of Account").orElseThrow().getTableId());
    }

    @Test
    void unknownFieldTypeIsRejected() {
        Fixture fx = Fixture.inMemory(tmp);
        ValidationException e = assertThrows(ValidationException.class,
                () -> fx.setups.putSheet(sheet("Loans", 52185729, "Amount", "Money"), "harry"));
        assertTrue(e.getMessage().contains("unknown type 'Money'"));
    }

    @Test
    void importIsAllOrNothing() {
        Fixture fx = Fixture.inMemory(tmp);
        SetupBundle bundle = new SetupBundle(null, List.of(
                sheet("Loan Recovery Header", 52187020, "No.", "Code20"),
                sheet("Salary Lines", -1, null, null)));
        assertThrows(ValidationException.class, () -> fx.setups.importBundle(bundle, "harry"));
        assertTrue(fx.setups.stored().templates().isEmpty(), "nothing saved when any part is invalid");
    }

    @Test
    void importSavesEveryPart() {
        Fixture fx = Fixture.inMemory(tmp);
        fx.setups.importBundle(new SetupBundle(null, List.of(sheet("Loan Recovery Header", 52187020, "No.", "Code20"))), "harry");
        assertEquals(52187020, fx.setups.effective().templates().find("Loan Recovery Header").orElseThrow().getTableId());
        assertFalse(fx.audit.list("SETUP", null, 10).stream().noneMatch(e -> e.action().equals("SETUP_IMPORTED")));
    }

    @Test
    void actorIsRequired() {
        Fixture fx = Fixture.inMemory(tmp);
        assertThrows(ValidationException.class, () -> fx.setups.putSheet(sheet("Loans", 1, null, null), " "));
    }
}
