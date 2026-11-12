package com.databridge.core.service;

import com.databridge.core.domain.Client;
import com.databridge.core.domain.MigrationRun;
import com.databridge.core.domain.Project;
import com.databridge.core.domain.RunState;
import com.databridge.core.domain.RunType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MigrationServiceTest {

    @TempDir Path tmp;

    @Test
    void clientCodeIsNormalisedAndUnique() {
        Fixture fx = Fixture.inMemory(tmp);
        Client c = fx.migrations.createClient(" azima ", "AZIMA DT SACCO", "harry");
        assertEquals("AZIMA", c.code());
        assertThrows(ValidationException.class, () -> fx.migrations.createClient("Azima", "Again", "harry"));
        assertThrows(ValidationException.class, () -> fx.migrations.createClient("A", "Too short", "harry"));
        assertThrows(ValidationException.class, () -> fx.migrations.createClient("AZ IMA", "Space", "harry"));
    }

    @Test
    void runsAreNumberedPerProjectAndCanBeClosed() {
        Fixture fx = Fixture.inMemory(tmp);
        Client c = fx.migrations.createClient("AZIMA", "AZIMA DT SACCO", "harry");
        Project p = fx.migrations.createProject(c.id(), "BC-2026", "BC go-live", "AZIMA DT SACCO", "harry");
        MigrationRun r1 = fx.migrations.openRun(p.id(), RunType.MOCK, LocalDate.of(2026, 11, 30), "harry");
        MigrationRun r2 = fx.migrations.openRun(p.id(), RunType.MOCK, LocalDate.of(2027, 1, 31), "harry");
        assertEquals(1, r1.runNo());
        assertEquals(2, r2.runNo());
        assertEquals(RunState.CLOSED, fx.migrations.closeRun(r1.id(), "harry").state());
        assertEquals(2, fx.migrations.runs(p.id()).size());
    }

    @Test
    void projectCodeIsUniquePerClientOnly() {
        Fixture fx = Fixture.inMemory(tmp);
        Client a = fx.migrations.createClient("AZIMA", "AZIMA", "harry");
        Client b = fx.migrations.createClient("DESACCO", "Defence SACCO", "harry");
        fx.migrations.createProject(a.id(), "BC", "Go-live", null, "harry");
        fx.migrations.createProject(b.id(), "BC", "Go-live", null, "harry");
        assertThrows(ValidationException.class, () -> fx.migrations.createProject(a.id(), "bc", "Dup", null, "harry"));
    }

    @Test
    void missingThingsAreNotFound() {
        Fixture fx = Fixture.inMemory(tmp);
        assertThrows(NotFoundException.class, () -> fx.migrations.createProject("nope", "BC", "x", null, "harry"));
        assertThrows(NotFoundException.class, () -> fx.migrations.run("nope"));
    }

    @Test
    void runNeedsTypeAndCutoff() {
        Fixture fx = Fixture.inMemory(tmp);
        Client c = fx.migrations.createClient("AZIMA", "AZIMA", "harry");
        Project p = fx.migrations.createProject(c.id(), "BC", "Go-live", null, "harry");
        assertThrows(ValidationException.class, () -> fx.migrations.openRun(p.id(), RunType.MOCK, null, "harry"));
        assertThrows(ValidationException.class, () -> fx.migrations.openRun(p.id(), null, LocalDate.now(), "harry"));
    }
}
