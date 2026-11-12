package com.databridge.store.jdbc;

import com.databridge.core.domain.AuditEvent;
import com.databridge.core.domain.Client;
import com.databridge.core.port.AuditRepository;
import com.databridge.core.port.ClientRepository;
import com.databridge.core.port.SetupRepository;
import com.databridge.core.setup.DataBridgeSetup;
import com.databridge.core.setup.FieldSetup;
import com.databridge.core.setup.FindingValueMode;
import com.databridge.core.setup.RetentionMode;
import com.databridge.core.setup.SheetSetup;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcRepositoriesTest {

    private final JdbcRepositories repos = new JdbcRepositories(TestDatabase.create());
    private final Instant now = Instant.parse("2027-01-31T08:00:00.123Z");

    @Test
    void generalSetupRoundTripsAndUpdatesInPlace() {
        SetupRepository s = repos.setups();
        assertTrue(s.loadGeneral().isEmpty(), "empty table means shipped defaults apply");

        DataBridgeSetup g = new DataBridgeSetup();
        g.getRetention().setMode(RetentionMode.KEEP_FOR_HOURS);
        g.getRetention().setKeepHours(48);
        g.getRetention().setSecureOverwrite(false);
        g.setFindingValueMode(FindingValueMode.NONE);
        s.saveGeneral(g, "harry", now);
        g.setMaxUploadMb(300);
        s.saveGeneral(g, "harry", now);

        DataBridgeSetup back = s.loadGeneral().orElseThrow();
        assertEquals(RetentionMode.KEEP_FOR_HOURS, back.getRetention().getMode());
        assertEquals(48, back.getRetention().getKeepHours());
        assertFalse(back.getRetention().isSecureOverwrite());
        assertEquals(FindingValueMode.NONE, back.getFindingValueMode());
        assertEquals(300, back.getMaxUploadMb());
    }

    @Test
    void templateSetupKeepsFieldOrderOptionsAndNulls() {
        SetupRepository s = repos.setups();
        SheetSetup sheet = new SheetSetup();
        sheet.setSheetName("Loan Recovery Header");
        sheet.setTableId(52187020);
        FieldSetup a = new FieldSetup();
        a.setCaption("No.");
        a.setType("Code20");
        a.setRequired(true);
        FieldSetup b = new FieldSetup();
        b.setCaption("Status");
        b.setType("Option");
        b.setOptions(List.of("", "Open", "Approved"));
        sheet.setFields(List.of(a, b));
        s.saveSheet(sheet, "harry", now);

        SheetSetup back = s.loadSheets().get(0);
        assertEquals(52187020, back.getTableId());
        assertNull(back.getCaptionRow());
        assertEquals("No.", back.getFields().get(0).getCaption());
        assertTrue(back.getFields().get(0).isRequired());
        assertEquals(List.of("", "Open", "Approved"), back.getFields().get(1).getOptions());

        sheet.setFields(List.of(a));
        s.saveSheet(sheet, "harry", now);
        assertEquals(1, s.loadSheets().get(0).getFields().size(), "saving replaces the field lines");

        assertTrue(s.deleteSheet("LOAN RECOVERY HEADER"));
        assertTrue(s.loadSheets().isEmpty());
        assertFalse(s.deleteSheet("Loan Recovery Header"));
    }

    @Test
    void clientCodeIsUniqueInTheDatabaseToo() {
        ClientRepository c = repos.clients();
        c.insert(new Client("1", "AZIMA", "AZIMA DT SACCO", now, "harry"));
        assertThrows(JdbcException.class, () -> c.insert(new Client("2", "AZIMA", "Dup", now, "harry")));
        Optional<Client> found = c.findByCode("AZIMA");
        assertEquals(now, found.orElseThrow().createdAt(), "timestamps keep millisecond precision");
    }

    @Test
    void auditFiltersAndReturnsNewestFirst() {
        AuditRepository a = repos.audit();
        a.append(new AuditEvent("1", now, "harry", "CLIENT_CREATED", "CLIENT", "c1", "AZIMA"));
        a.append(new AuditEvent("2", now.plusSeconds(1), "harry", "RUN_OPENED", "RUN", "r1", "MOCK run 1"));
        a.append(new AuditEvent("3", now.plusSeconds(2), "harry", "RUN_CLOSED", "RUN", "r1", "Run 1"));

        assertEquals("3", a.list(null, null, 10).get(0).id());
        assertEquals(2, a.list("RUN", "r1", 10).size());
        assertEquals(1, a.list("RUN", null, 1).size());
        assertEquals("c1", a.list("CLIENT", null, 10).get(0).entityId());
    }

    @Test
    void migrationCreatesEveryTable() {
        javax.sql.DataSource ds = TestDatabase.create();
        Jdbc jdbc = new Jdbc(ds);
        List<String> tables = jdbc.query("SELECT LOWER(TABLE_NAME) AS t FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_SCHEMA = 'PUBLIC' ORDER BY t", rs -> rs.getString("t"));
        assertEquals(List.of("audit_event", "client", "databridge_schema_history", "databridge_setup", "dataset_version",
                "migration_run", "project", "template_setup_field", "template_setup_sheet"), tables);
    }
}
