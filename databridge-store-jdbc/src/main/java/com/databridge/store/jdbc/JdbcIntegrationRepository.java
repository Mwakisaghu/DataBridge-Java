package com.databridge.store.jdbc;

import com.databridge.core.domain.ImportRecord;
import com.databridge.core.domain.PackageJob;
import com.databridge.core.domain.TemplateKind;
import com.databridge.core.integration.IntegrationSetup;
import com.databridge.core.port.IntegrationRepository;
import com.databridge.core.setup.SetupLoader;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** JDBC store for the Integration Setup, packages and import tracking. Portable SQL. */
public final class JdbcIntegrationRepository implements IntegrationRepository {

    private static final String SETUP_ID = "integration";
    private final Jdbc jdbc;

    public JdbcIntegrationRepository(DataSource ds) {
        this.jdbc = new Jdbc(ds);
    }

    @Override public Optional<IntegrationSetup> setup() {
        return jdbc.one("SELECT definition FROM integration_setup WHERE setup_id = ?", rs -> read(rs.getString("definition"),
                IntegrationSetup.class), SETUP_ID);
    }

    @Override public void saveSetup(IntegrationSetup s, String actor, Instant at) {
        String json = json(s);
        jdbc.inTransaction(c -> {
            int n = Jdbc.update(c, "UPDATE integration_setup SET definition = ?, modified_at = ?, modified_by = ? WHERE setup_id = ?",
                    json, at, actor, SETUP_ID);
            if (n == 0) {
                Jdbc.update(c, "INSERT INTO integration_setup (setup_id, definition, modified_at, modified_by) VALUES (?, ?, ?, ?)",
                        SETUP_ID, json, at, actor);
            }
        });
    }

    private static final String PKG = "id, run_id, check_id, template_kind, file_name, sha256_in, sha256_out, tables_json, created_at, created_by";

    @Override public void insertPackage(PackageJob j) {
        jdbc.update("INSERT INTO package_job (" + PKG + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", j.id(), j.runId(), j.checkId(),
                j.kind(), cut(j.fileName(), 255), j.sha256In(), j.sha256Out(), json(j.tables()), j.createdAt(), j.createdBy());
    }

    @Override public List<PackageJob> packages(String runId) {
        return jdbc.query("SELECT " + PKG + " FROM package_job WHERE run_id = ? ORDER BY created_at DESC, id", rs -> new PackageJob(
                rs.getString("id"), rs.getString("run_id"), rs.getString("check_id"), TemplateKind.valueOf(rs.getString("template_kind")),
                rs.getString("file_name"), rs.getString("sha256_in"), rs.getString("sha256_out"),
                List.of(read(rs.getString("tables_json"), PackageJob.PackageTable[].class)),
                Jdbc.instant(rs, "created_at"), rs.getString("created_by")), runId);
    }

    private static final String IMP = "run_id, sheet, table_id, expected_rows, imported_rows, bc_count, status, note, updated_at, updated_by";

    @Override public void upsertImport(ImportRecord r) {
        jdbc.inTransaction(c -> {
            int n = Jdbc.update(c, "UPDATE import_record SET table_id = ?, expected_rows = ?, imported_rows = ?, bc_count = ?, status = ?, "
                            + "note = ?, updated_at = ?, updated_by = ? WHERE run_id = ? AND sheet = ?",
                    r.tableId(), r.expectedRows(), r.importedRows(), r.bcCount(), r.status(), cut(r.note(), 500), r.updatedAt(),
                    r.updatedBy(), r.runId(), r.sheet());
            if (n == 0) {
                Jdbc.update(c, "INSERT INTO import_record (" + IMP + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", r.runId(), cut(r.sheet(), 250),
                        r.tableId(), r.expectedRows(), r.importedRows(), r.bcCount(), r.status(), cut(r.note(), 500), r.updatedAt(), r.updatedBy());
            }
        });
    }

    @Override public List<ImportRecord> imports(String runId) {
        return jdbc.query("SELECT " + IMP + " FROM import_record WHERE run_id = ? ORDER BY updated_at, sheet",
                JdbcIntegrationRepository::imp, runId);
    }

    private static ImportRecord imp(ResultSet rs) throws SQLException {
        long bc = rs.getLong("bc_count");
        Long bcCount = rs.wasNull() ? null : bc;
        return new ImportRecord(rs.getString("run_id"), rs.getString("sheet"), Jdbc.integer(rs, "table_id"), rs.getInt("expected_rows"),
                Jdbc.integer(rs, "imported_rows"), bcCount, ImportRecord.Status.valueOf(rs.getString("status")), rs.getString("note"),
                Jdbc.instant(rs, "updated_at"), rs.getString("updated_by"));
    }

    private static String json(Object o) {
        try {
            return SetupLoader.mapper().writeValueAsString(o);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> T read(String json, Class<T> type) {
        try {
            return SetupLoader.mapper().readValue(json, type);
        } catch (IOException e) {
            throw new IllegalStateException("Stored integration data cannot be read: " + e.getMessage(), e);
        }
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
