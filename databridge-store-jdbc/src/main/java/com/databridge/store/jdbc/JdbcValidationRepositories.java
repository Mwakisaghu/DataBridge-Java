package com.databridge.store.jdbc;

import com.databridge.core.domain.FindingCount;
import com.databridge.core.domain.FindingFilter;
import com.databridge.core.domain.StoredFinding;
import com.databridge.core.domain.ValidationCheck;
import com.databridge.core.finding.FindingStatus;
import com.databridge.core.finding.Severity;
import com.databridge.core.port.CheckRepository;
import com.databridge.core.port.FindingRepository;
import com.databridge.core.port.RuleRepository;
import com.databridge.core.run.RunStatus;
import com.databridge.core.setup.SetupLoader;
import com.databridge.core.validation.RuleDef;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** JDBC repositories for validation checks, findings and the Rule Setup. Portable SQL. */
public final class JdbcValidationRepositories {

    private final Jdbc jdbc;

    public JdbcValidationRepositories(DataSource ds) {
        this.jdbc = new Jdbc(ds);
    }

    public CheckRepository checks() { return new Checks(); }
    public FindingRepository findings() { return new Findings(); }
    public RuleRepository rules() { return new Rules(); }

    private final class Checks implements CheckRepository {
        private static final String COLS = "id, run_id, check_no, status, dataset_ids, rows_checked, critical_count, warning_count, "
                + "info_count, readiness, sheets_json, skipped_json, error, created_at, created_by";
        @Override public void insert(ValidationCheck c) {
            jdbc.update("INSERT INTO validation_check (" + COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    c.id(), c.runId(), c.checkNo(), c.status(), c.datasetIds(), c.rows(), c.critical(), c.warning(), c.info(),
                    c.readiness(), c.sheetsJson(), c.skippedJson(), c.error(), c.createdAt(), c.createdBy());
        }
        @Override public Optional<ValidationCheck> find(String id) {
            return jdbc.one("SELECT " + COLS + " FROM validation_check WHERE id = ?", Checks::map, id);
        }
        @Override public List<ValidationCheck> listByRun(String runId) {
            return jdbc.query("SELECT " + COLS + " FROM validation_check WHERE run_id = ? ORDER BY check_no DESC", Checks::map, runId);
        }
        @Override public int maxCheckNo(String runId) {
            return jdbc.one("SELECT COALESCE(MAX(check_no), 0) AS n FROM validation_check WHERE run_id = ?", rs -> rs.getInt("n"), runId)
                    .orElse(0);
        }
        static ValidationCheck map(ResultSet rs) throws SQLException {
            return new ValidationCheck(rs.getString("id"), rs.getString("run_id"), rs.getInt("check_no"),
                    RunStatus.valueOf(rs.getString("status")), rs.getString("dataset_ids"), rs.getInt("rows_checked"),
                    rs.getInt("critical_count"), rs.getInt("warning_count"), rs.getInt("info_count"), rs.getBigDecimal("readiness"),
                    rs.getString("sheets_json"), rs.getString("skipped_json"), rs.getString("error"),
                    Jdbc.instant(rs, "created_at"), rs.getString("created_by"));
        }
    }

    private final class Findings implements FindingRepository {
        private static final String COLS = "id, check_id, run_id, template_kind, sheet, row_no, column_ref, field, rule_id, rule_type, "
                + "severity, severity_rank, message, value_masked, auto_fixable, status, waiver_reason, requested_by, requested_at, "
                + "decided_by, decided_at, decision_note";
        @Override public void insertAll(List<StoredFinding> fs) {
            if (fs.isEmpty()) return;
            List<Object[]> rows = new ArrayList<>();
            for (StoredFinding f : fs) {
                rows.add(new Object[] {f.id(), f.checkId(), f.runId(), f.kind(), cut(f.sheet(), 250), f.rowNo(), f.columnRef(),
                        cut(f.field(), 250), f.ruleId(), f.ruleType(), f.severity(), f.severity().ordinal(), cut(f.message(), 500),
                        cut(f.value(), 300), f.autoFixable(), f.status(), f.waiverReason(), f.requestedBy(), f.requestedAt(),
                        f.decidedBy(), f.decidedAt(), f.decisionNote()});
            }
            jdbc.inTransaction(c -> Jdbc.batch(c, "INSERT INTO finding (" + COLS + ") VALUES "
                    + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows));
        }
        @Override public Optional<StoredFinding> find(String id) {
            return jdbc.one("SELECT " + COLS + " FROM finding WHERE id = ?", Findings::map, id);
        }
        @Override public void update(StoredFinding f) {
            int n = jdbc.update("UPDATE finding SET status = ?, waiver_reason = ?, requested_by = ?, requested_at = ?, decided_by = ?, "
                    + "decided_at = ?, decision_note = ? WHERE id = ?", f.status(), f.waiverReason(), f.requestedBy(),
                    f.requestedAt(), f.decidedBy(), f.decidedAt(), f.decisionNote(), f.id());
            if (n != 1) throw new IllegalStateException("No finding " + f.id());
        }
        @Override public List<StoredFinding> list(String checkId, FindingFilter filter, int offset, int limit) {
            List<Object> params = new ArrayList<>();
            String where = where(checkId, filter, params);
            List<StoredFinding> rows = jdbc.queryLimited("SELECT " + COLS + " FROM finding " + where
                    + " ORDER BY severity_rank, sheet, row_no, id", offset + limit < 0 ? 0 : offset + limit, Findings::map, params.toArray());
            return rows.subList(Math.min(offset, rows.size()), rows.size());
        }
        @Override public int count(String checkId, FindingFilter filter) {
            List<Object> params = new ArrayList<>();
            return jdbc.one("SELECT COUNT(*) AS n FROM finding " + where(checkId, filter, params), rs -> rs.getInt("n"),
                    params.toArray()).orElse(0);
        }
        @Override public List<FindingCount> counts(String checkId) {
            return jdbc.query("SELECT severity, status, COUNT(*) AS n FROM finding WHERE check_id = ? GROUP BY severity, status",
                    rs -> new FindingCount(Severity.valueOf(rs.getString("severity")), FindingStatus.valueOf(rs.getString("status")),
                            rs.getInt("n")), checkId);
        }
        private static String where(String checkId, FindingFilter f, List<Object> params) {
            StringBuilder sb = new StringBuilder("WHERE check_id = ?");
            params.add(checkId);
            if (f.severity() != null) { sb.append(" AND severity = ?"); params.add(f.severity()); }
            if (f.status() != null) { sb.append(" AND status = ?"); params.add(f.status()); }
            if (f.sheet() != null) { sb.append(" AND sheet = ?"); params.add(f.sheet()); }
            if (f.ruleType() != null) { sb.append(" AND rule_type = ?"); params.add(f.ruleType()); }
            return sb.toString();
        }
        static StoredFinding map(ResultSet rs) throws SQLException {
            return new StoredFinding(rs.getString("id"), rs.getString("check_id"), rs.getString("run_id"), rs.getString("template_kind"),
                    rs.getString("sheet"), rs.getInt("row_no"), rs.getString("column_ref"), rs.getString("field"), rs.getString("rule_id"),
                    rs.getString("rule_type"), Severity.valueOf(rs.getString("severity")), rs.getString("message"),
                    rs.getString("value_masked"), rs.getBoolean("auto_fixable"), FindingStatus.valueOf(rs.getString("status")),
                    rs.getString("waiver_reason"), rs.getString("requested_by"), Jdbc.instant(rs, "requested_at"),
                    rs.getString("decided_by"), Jdbc.instant(rs, "decided_at"), rs.getString("decision_note"));
        }
        private static String cut(String s, int n) {
            return s == null || s.length() <= n ? s : s.substring(0, n);
        }
    }

    private final class Rules implements RuleRepository {
        @Override public List<RuleDef> list() {
            return jdbc.query("SELECT definition FROM rule_setup ORDER BY rule_id", rs -> {
                try {
                    return SetupLoader.mapper().readValue(rs.getString("definition"), RuleDef.class);
                } catch (IOException e) {
                    throw new SQLException("Stored rule cannot be read: " + e.getMessage(), e);
                }
            });
        }
        @Override public void save(RuleDef r, String actor, Instant at) {
            String json;
            try {
                json = SetupLoader.mapper().writeValueAsString(r);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            jdbc.inTransaction(c -> {
                int n = Jdbc.update(c, "UPDATE rule_setup SET definition = ?, modified_at = ?, modified_by = ? WHERE rule_id = ?",
                        json, at, actor, r.getId());
                if (n == 0) {
                    Jdbc.update(c, "INSERT INTO rule_setup (rule_id, definition, modified_at, modified_by) VALUES (?, ?, ?, ?)",
                            r.getId(), json, at, actor);
                }
            });
        }
        @Override public boolean delete(String id) {
            return jdbc.update("DELETE FROM rule_setup WHERE rule_id = ?", id) > 0;
        }
    }
}
