package com.databridge.store.jdbc;

import com.databridge.core.domain.ImportRecord;
import com.databridge.core.domain.PackageJob;
import com.databridge.core.domain.RunType;
import com.databridge.core.domain.TemplateKind;
import com.databridge.core.integration.EntityMap;
import com.databridge.core.integration.IntegrationSetup;
import com.databridge.core.service.AuditLog;
import com.databridge.core.service.MigrationService;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcIntegrationRepositoryTest {

    @Test
    void setupPackagesAndImportsRoundTrip() {
        DataSource ds = TestDatabase.create();
        JdbcRepositories r = new JdbcRepositories(ds);
        Clock clock = Clock.fixed(Instant.parse("2027-01-31T08:00:00Z"), ZoneOffset.UTC);
        MigrationService m = new MigrationService(r.clients(), r.projects(), r.runs(), new AuditLog(r.audit(), clock), clock);
        String runId = m.openRun(m.createProject(m.createClient("INTEG", "I SACCO", "h").id(), "BC", "x", null, "h").id(),
                RunType.MOCK, LocalDate.of(2026, 10, 31), "h").id();
        JdbcIntegrationRepository repo = new JdbcIntegrationRepository(ds);
        Instant at = clock.instant();

        assertTrue(repo.setup().isEmpty());
        IntegrationSetup s = new IntegrationSetup();
        s.setTenantId("t1");
        s.setEntities(new ArrayList<>(List.of(new EntityMap("Members", "api/sky/sacco/v1.0", "members"))));
        repo.saveSetup(s, "admin", at);
        s.setCompanyId("c1");
        repo.saveSetup(s, "admin", at);
        IntegrationSetup back = repo.setup().orElseThrow();
        assertEquals("c1", back.getCompanyId());
        assertEquals("members", back.getEntities().get(0).getEntitySet());

        PackageJob job = new PackageJob("p1", runId, null, TemplateKind.STATIC, "S.xlsx", "a".repeat(64), "b".repeat(64),
                List.of(new PackageJob.PackageTable(1, "Members", "Members", 52185700, 150)), at, "h");
        repo.insertPackage(job);
        assertEquals(150, repo.packages(runId).get(0).tables().get(0).rows());

        repo.upsertImport(new ImportRecord(runId, "Members", 52185700, 150, null, null, ImportRecord.Status.PENDING, null, at, "h"));
        repo.upsertImport(new ImportRecord(runId, "Members", 52185700, 150, 150, 150L, ImportRecord.Status.MATCHED, null, at, "h"));
        repo.upsertImport(new ImportRecord(runId, "Loans", null, 85, null, null, ImportRecord.Status.PENDING, null, at, "h"));
        List<ImportRecord> imps = repo.imports(runId);
        assertEquals(2, imps.size());
        ImportRecord loans = imps.stream().filter(i -> i.sheet().equals("Loans")).findFirst().orElseThrow();
        assertNull(loans.bcCount());
        assertNull(loans.importedRows());
        assertEquals(150L, imps.stream().filter(i -> i.sheet().equals("Members")).findFirst().orElseThrow().bcCount());

        JdbcSignOffRepository so = new JdbcSignOffRepository(ds);
        assertTrue(so.find(runId).isEmpty());
        so.save(new com.databridge.core.domain.SignOff(runId, com.databridge.core.domain.SignOff.Status.REQUESTED, null, "[]", "h", at,
                null, null, null, null));
        so.save(new com.databridge.core.domain.SignOff(runId, com.databridge.core.domain.SignOff.Status.APPROVED, "DB-X", "[]", "h", at,
                null, "f", at, "ok"));
        assertEquals("DB-X", so.find(runId).orElseThrow().certificateNo());
    }
}
