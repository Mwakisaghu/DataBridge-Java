package com.databridge.store.jdbc;

import com.databridge.core.domain.AuditEvent;
import com.databridge.core.domain.Client;
import com.databridge.core.domain.DatasetVersion;
import com.databridge.core.domain.MigrationRun;
import com.databridge.core.domain.Project;
import com.databridge.core.domain.RunState;
import com.databridge.core.domain.RunType;
import com.databridge.core.domain.TemplateKind;
import com.databridge.core.port.AuditRepository;
import com.databridge.core.port.ClientRepository;
import com.databridge.core.port.DatasetRepository;
import com.databridge.core.port.ProjectRepository;
import com.databridge.core.port.RunRepository;
import com.databridge.core.port.SetupRepository;
import com.databridge.core.run.RunStatus;
import com.databridge.core.setup.DataBridgeSetup;
import com.databridge.core.setup.FieldSetup;
import com.databridge.core.setup.FindingValueMode;
import com.databridge.core.setup.RetentionMode;
import com.databridge.core.setup.RetentionSetup;
import com.databridge.core.setup.SheetSetup;
import com.databridge.core.setup.TemplateSetup;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Plain-JDBC implementations of the core repository ports. Portable SQL (SQL Server, H2). */
public final class JdbcRepositories {

    private final Jdbc jdbc;

    public JdbcRepositories(DataSource dataSource) {
        this.jdbc = new Jdbc(dataSource);
    }

    public ClientRepository clients() { return new Clients(); }
    public ProjectRepository projects() { return new Projects(); }
    public RunRepository runs() { return new Runs(); }
    public DatasetRepository datasets() { return new Datasets(); }
    public AuditRepository audit() { return new Audit(); }
    public SetupRepository setups() { return new Setups(); }

    private final class Clients implements ClientRepository {
        private static final String COLS = "id, code, name, created_at, created_by";
        @Override public void insert(Client c) {
            jdbc.update("INSERT INTO client (" + COLS + ") VALUES (?, ?, ?, ?, ?)",
                    c.id(), c.code(), c.name(), c.createdAt(), c.createdBy());
        }
        @Override public Optional<Client> find(String id) {
            return jdbc.one("SELECT " + COLS + " FROM client WHERE id = ?", Clients::map, id);
        }
        @Override public Optional<Client> findByCode(String code) {
            return jdbc.one("SELECT " + COLS + " FROM client WHERE code = ?", Clients::map, code);
        }
        @Override public List<Client> list() {
            return jdbc.query("SELECT " + COLS + " FROM client ORDER BY code", Clients::map);
        }
        static Client map(ResultSet rs) throws SQLException {
            return new Client(rs.getString("id"), rs.getString("code"), rs.getString("name"),
                    Jdbc.instant(rs, "created_at"), rs.getString("created_by"));
        }
    }

    private final class Projects implements ProjectRepository {
        private static final String COLS = "id, client_id, code, name, bc_company, created_at, created_by";
        @Override public void insert(Project p) {
            jdbc.update("INSERT INTO project (" + COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?)",
                    p.id(), p.clientId(), p.code(), p.name(), p.bcCompany(), p.createdAt(), p.createdBy());
        }
        @Override public Optional<Project> find(String id) {
            return jdbc.one("SELECT " + COLS + " FROM project WHERE id = ?", Projects::map, id);
        }
        @Override public Optional<Project> findByCode(String clientId, String code) {
            return jdbc.one("SELECT " + COLS + " FROM project WHERE client_id = ? AND code = ?", Projects::map, clientId, code);
        }
        @Override public List<Project> listByClient(String clientId) {
            return jdbc.query("SELECT " + COLS + " FROM project WHERE client_id = ? ORDER BY code", Projects::map, clientId);
        }
        static Project map(ResultSet rs) throws SQLException {
            return new Project(rs.getString("id"), rs.getString("client_id"), rs.getString("code"), rs.getString("name"),
                    rs.getString("bc_company"), Jdbc.instant(rs, "created_at"), rs.getString("created_by"));
        }
    }

    private final class Runs implements RunRepository {
        private static final String COLS = "id, project_id, run_no, run_type, cutoff_date, state, created_at, created_by";
        @Override public void insert(MigrationRun r) {
            jdbc.update("INSERT INTO migration_run (" + COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    r.id(), r.projectId(), r.runNo(), r.type(), r.cutoffDate(), r.state(), r.createdAt(), r.createdBy());
        }
        @Override public void updateState(MigrationRun r) {
            if (jdbc.update("UPDATE migration_run SET state = ? WHERE id = ?", r.state(), r.id()) != 1) {
                throw new IllegalStateException("No run " + r.id());
            }
        }
        @Override public Optional<MigrationRun> find(String id) {
            return jdbc.one("SELECT " + COLS + " FROM migration_run WHERE id = ?", Runs::map, id);
        }
        @Override public List<MigrationRun> listByProject(String projectId) {
            return jdbc.query("SELECT " + COLS + " FROM migration_run WHERE project_id = ? ORDER BY run_no", Runs::map, projectId);
        }
        @Override public int maxRunNo(String projectId) {
            return jdbc.one("SELECT COALESCE(MAX(run_no), 0) AS n FROM migration_run WHERE project_id = ?",
                    rs -> rs.getInt("n"), projectId).orElse(0);
        }
        static MigrationRun map(ResultSet rs) throws SQLException {
            return new MigrationRun(rs.getString("id"), rs.getString("project_id"), rs.getInt("run_no"),
                    RunType.valueOf(rs.getString("run_type")), rs.getObject("cutoff_date", LocalDate.class),
                    RunState.valueOf(rs.getString("state")), Jdbc.instant(rs, "created_at"), rs.getString("created_by"));
        }
    }

    private final class Datasets implements DatasetRepository {
        private static final String COLS = "id, run_id, template_kind, version_no, file_name, sha256, size_bytes, "
                + "sheet_count, warning_count, status, error, schema_json, uploaded_at, uploaded_by";
        @Override public void insert(DatasetVersion d) {
            jdbc.update("INSERT INTO dataset_version (" + COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    d.id(), d.runId(), d.kind(), d.versionNo(), d.fileName(), d.sha256(), d.sizeBytes(), d.sheetCount(),
                    d.warningCount(), d.status(), d.error(), d.schemaJson(), d.uploadedAt(), d.uploadedBy());
        }
        @Override public Optional<DatasetVersion> find(String id) {
            return jdbc.one("SELECT " + COLS + " FROM dataset_version WHERE id = ?", Datasets::map, id);
        }
        @Override public List<DatasetVersion> listByRun(String runId) {
            return jdbc.query("SELECT " + COLS + " FROM dataset_version WHERE run_id = ? ORDER BY template_kind, version_no DESC",
                    Datasets::map, runId);
        }
        @Override public int maxVersion(String runId, TemplateKind kind) {
            return jdbc.one("SELECT COALESCE(MAX(version_no), 0) AS n FROM dataset_version WHERE run_id = ? AND template_kind = ?",
                    rs -> rs.getInt("n"), runId, kind).orElse(0);
        }
        static DatasetVersion map(ResultSet rs) throws SQLException {
            return new DatasetVersion(rs.getString("id"), rs.getString("run_id"),
                    TemplateKind.valueOf(rs.getString("template_kind")), rs.getInt("version_no"), rs.getString("file_name"),
                    rs.getString("sha256"), rs.getLong("size_bytes"), rs.getInt("sheet_count"), rs.getInt("warning_count"),
                    RunStatus.valueOf(rs.getString("status")), rs.getString("error"), rs.getString("schema_json"),
                    Jdbc.instant(rs, "uploaded_at"), rs.getString("uploaded_by"));
        }
    }

    private final class Audit implements AuditRepository {
        private static final String COLS = "id, occurred_at, actor, action, entity_type, entity_id, details";
        @Override public void append(AuditEvent e) {
            jdbc.update("INSERT INTO audit_event (" + COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?)",
                    e.id(), e.occurredAt(), e.actor(), e.action(), e.entityType(), e.entityId(), e.details());
        }
        @Override public List<AuditEvent> list(String entityType, String entityId, int limit) {
            StringBuilder sql = new StringBuilder("SELECT TOP ").append(Math.max(1, Math.min(limit, 1000)))
                    .append(" ").append(COLS).append(" FROM audit_event WHERE 1 = 1");
            List<Object> params = new ArrayList<>();
            if (entityType != null) { sql.append(" AND entity_type = ?"); params.add(entityType); }
            if (entityId != null) { sql.append(" AND entity_id = ?"); params.add(entityId); }
            sql.append(" ORDER BY occurred_at DESC, id DESC");
            return jdbc.query(sql.toString(), rs -> new AuditEvent(rs.getString("id"), Jdbc.instant(rs, "occurred_at"),
                    rs.getString("actor"), rs.getString("action"), rs.getString("entity_type"), rs.getString("entity_id"),
                    rs.getString("details")), params.toArray());
        }
    }

    private final class Setups implements SetupRepository {
        private static final String KEY = "DEFAULT";

        @Override public Optional<DataBridgeSetup> loadGeneral() {
            return jdbc.one("SELECT workspace_dir, retention_mode, keep_hours, max_run_hours, secure_overwrite, "
                    + "sweep_on_startup, finding_value_mode, caption_scan_rows, max_upload_mb FROM databridge_setup WHERE setup_key = ?",
                    rs -> {
                        DataBridgeSetup s = new DataBridgeSetup();
                        RetentionSetup r = new RetentionSetup();
                        s.setWorkspaceDir(rs.getString("workspace_dir"));
                        r.setMode(RetentionMode.valueOf(rs.getString("retention_mode")));
                        r.setKeepHours(rs.getInt("keep_hours"));
                        r.setMaxRunHours(rs.getInt("max_run_hours"));
                        r.setSecureOverwrite(rs.getBoolean("secure_overwrite"));
                        r.setSweepOnStartup(rs.getBoolean("sweep_on_startup"));
                        s.setRetention(r);
                        s.setFindingValueMode(FindingValueMode.valueOf(rs.getString("finding_value_mode")));
                        s.setCaptionScanRows(rs.getInt("caption_scan_rows"));
                        s.setMaxUploadMb(rs.getInt("max_upload_mb"));
                        return s;
                    }, KEY);
        }

        @Override public void saveGeneral(DataBridgeSetup s, String actor, Instant at) {
            RetentionSetup r = s.getRetention();
            Object[] values = {s.getWorkspaceDir(), r.getMode(), r.getKeepHours(), r.getMaxRunHours(), r.isSecureOverwrite(),
                    r.isSweepOnStartup(), s.getFindingValueMode(), s.getCaptionScanRows(), s.getMaxUploadMb(), at, actor, KEY};
            jdbc.inTransaction(c -> {
                int n = Jdbc.update(c, "UPDATE databridge_setup SET workspace_dir = ?, retention_mode = ?, keep_hours = ?, "
                        + "max_run_hours = ?, secure_overwrite = ?, sweep_on_startup = ?, finding_value_mode = ?, "
                        + "caption_scan_rows = ?, max_upload_mb = ?, modified_at = ?, modified_by = ? WHERE setup_key = ?", values);
                if (n == 0) {
                    Jdbc.update(c, "INSERT INTO databridge_setup (workspace_dir, retention_mode, keep_hours, max_run_hours, "
                            + "secure_overwrite, sweep_on_startup, finding_value_mode, caption_scan_rows, max_upload_mb, "
                            + "modified_at, modified_by, setup_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", values);
                }
            });
        }

        @Override public List<SheetSetup> loadSheets() {
            Map<String, SheetSetup> sheets = new LinkedHashMap<>();
            for (SheetSetup s : jdbc.query("SELECT sheet_key, sheet_name, table_name, table_id, caption_row, purpose "
                    + "FROM template_setup_sheet ORDER BY sheet_key", rs -> {
                        SheetSetup s = new SheetSetup();
                        s.setSheetName(rs.getString("sheet_name"));
                        s.setTableName(rs.getString("table_name"));
                        s.setTableId(Jdbc.integer(rs, "table_id"));
                        s.setCaptionRow(Jdbc.integer(rs, "caption_row"));
                        s.setPurpose(rs.getString("purpose"));
                        return s;
                    })) {
                s.setFields(new ArrayList<>());
                sheets.put(TemplateSetup.normalise(s.getSheetName()), s);
            }
            for (Object[] row : jdbc.query("SELECT sheet_key, caption, field_type, options, required "
                    + "FROM template_setup_field ORDER BY sheet_key, line_no",
                    rs -> new Object[] {rs.getString("sheet_key"), rs.getString("caption"), rs.getString("field_type"),
                            rs.getString("options"), rs.getBoolean("required")})) {
                SheetSetup s = sheets.get((String) row[0]);
                if (s == null) continue;
                FieldSetup f = new FieldSetup();
                f.setCaption((String) row[1]);
                f.setType((String) row[2]);
                String opts = (String) row[3];
                f.setOptions(opts == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(opts.split("\n", -1))));
                f.setRequired((Boolean) row[4]);
                s.getFields().add(f);
            }
            return List.copyOf(sheets.values());
        }

        @Override public void saveSheet(SheetSetup s, String actor, Instant at) {
            String key = TemplateSetup.normalise(s.getSheetName());
            jdbc.inTransaction(c -> {
                Jdbc.update(c, "DELETE FROM template_setup_field WHERE sheet_key = ?", key);
                int n = Jdbc.update(c, "UPDATE template_setup_sheet SET sheet_name = ?, table_name = ?, table_id = ?, "
                        + "caption_row = ?, purpose = ?, modified_at = ?, modified_by = ? WHERE sheet_key = ?",
                        s.getSheetName(), s.getTableName(), s.getTableId(), s.getCaptionRow(), s.getPurpose(), at, actor, key);
                if (n == 0) {
                    Jdbc.update(c, "INSERT INTO template_setup_sheet (sheet_key, sheet_name, table_name, table_id, caption_row, "
                            + "purpose, modified_at, modified_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                            key, s.getSheetName(), s.getTableName(), s.getTableId(), s.getCaptionRow(), s.getPurpose(), at, actor);
                }
                int line = 10000;
                for (FieldSetup f : s.getFields()) {
                    Jdbc.update(c, "INSERT INTO template_setup_field (sheet_key, line_no, caption, field_type, options, required) "
                            + "VALUES (?, ?, ?, ?, ?, ?)", key, line, f.getCaption(), f.getType(),
                            f.getOptions().isEmpty() ? null : String.join("\n", f.getOptions()), f.isRequired());
                    line += 10000;
                }
            });
        }

        @Override public boolean deleteSheet(String sheetName) {
            String key = TemplateSetup.normalise(sheetName);
            boolean[] removed = {false};
            jdbc.inTransaction(c -> {
                Jdbc.update(c, "DELETE FROM template_setup_field WHERE sheet_key = ?", key);
                removed[0] = Jdbc.update(c, "DELETE FROM template_setup_sheet WHERE sheet_key = ?", key) > 0;
            });
            return removed[0];
        }
    }
}
