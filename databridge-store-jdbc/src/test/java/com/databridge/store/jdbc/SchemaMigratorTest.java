package com.databridge.store.jdbc;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaMigratorTest {

    @Test
    void appliesPendingScriptsOnceAndRecordsThem() {
        DataSource ds = TestDatabase.empty();
        List<SchemaMigrator.Applied> first = new SchemaMigrator(ds).migrate();
        assertEquals(1, first.size());
        assertEquals("V1__baseline.sql", first.get(0).script());

        assertTrue(new SchemaMigrator(ds).migrate().isEmpty(), "second start applies nothing");
        int rows = new Jdbc(ds).one("SELECT COUNT(*) AS n FROM databridge_schema_history", rs -> rs.getInt("n")).orElse(0);
        assertEquals(1, rows);
    }

    @Test
    void refusesAScriptChangedAfterItWasApplied() {
        DataSource ds = TestDatabase.empty();
        new SchemaMigrator(ds).migrate();
        new Jdbc(ds).update("UPDATE databridge_schema_history SET checksum = ? WHERE version = 1", "0".repeat(64));
        MigrationException e = assertThrows(MigrationException.class, () -> new SchemaMigrator(ds).migrate());
        assertTrue(e.getMessage().contains("has changed since it was applied"), e.getMessage());
    }

    @Test
    void refusesADatabaseNewerThanTheCode() {
        DataSource ds = TestDatabase.empty();
        new SchemaMigrator(ds).migrate();
        new Jdbc(ds).update("INSERT INTO databridge_schema_history (version, script, checksum, applied_at) VALUES (?, ?, ?, ?)",
                99, "V99__future.sql", "0".repeat(64), java.time.Instant.now());
        assertThrows(MigrationException.class, () -> new SchemaMigrator(ds).migrate());
    }

    @Test
    void splitsOnSemicolonsAndGoAndDropsComments() {
        List<String> s = SchemaMigrator.statements("-- header\nCREATE TABLE a (x INT); -- trailing\nGO\nCREATE TABLE b (y INT)\ngo\n");
        assertEquals(List.of("CREATE TABLE a (x INT)", "CREATE TABLE b (y INT)"), s);
    }
}
