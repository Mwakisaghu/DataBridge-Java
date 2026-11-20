package com.databridge.store.jdbc;

import com.databridge.core.cleaning.CleanAction;
import com.databridge.core.cleaning.CleanChange;
import com.databridge.core.cleaning.CleanRule;
import com.databridge.core.domain.CleaningJob;
import com.databridge.core.domain.TemplateKind;
import com.databridge.core.port.CleaningRepository;
import com.databridge.core.run.RunStatus;
import com.databridge.core.setup.SetupLoader;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** JDBC store for the Cleaning Setup, cleaning jobs and their change logs. Portable SQL. */
public final class JdbcCleaningRepository implements CleaningRepository {

    private final Jdbc jdbc;

    public JdbcCleaningRepository(DataSource ds) {
        this.jdbc = new Jdbc(ds);
    }

    @Override public List<CleanRule> rules() {
        return jdbc.query("SELECT definition FROM cleaning_setup ORDER BY rule_id", rs -> {
            try {
                return SetupLoader.mapper().readValue(rs.getString("definition"), CleanRule.class);
            } catch (IOException e) {
                throw new SQLException("Stored cleaning rule cannot be read: " + e.getMessage(), e);
            }
        });
    }

    @Override public void saveRule(CleanRule r, String actor, Instant at) {
        String json;
        try {
            json = SetupLoader.mapper().writeValueAsString(r);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        jdbc.inTransaction(c -> {
            int n = Jdbc.update(c, "UPDATE cleaning_setup SET definition = ?, modified_at = ?, modified_by = ? WHERE rule_id = ?",
                    json, at, actor, r.getId());
            if (n == 0) {
                Jdbc.update(c, "INSERT INTO cleaning_setup (rule_id, definition, modified_at, modified_by) VALUES (?, ?, ?, ?)",
                        r.getId(), json, at, actor);
            }
        });
    }

    @Override public boolean deleteRule(String id) {
        return jdbc.update("DELETE FROM cleaning_setup WHERE rule_id = ?", id) > 0;
    }

    private static final String JOB = "id, run_id, template_kind, file_name, sha256_in, size_in, sha256_out, cells_changed, rows_emptied, "
            + "status, error, created_at, created_by";

    @Override public void insertJob(CleaningJob j, List<CleanChange> changes) {
        List<Object[]> rows = new ArrayList<>();
        int n = 1;
        for (CleanChange ch : changes) {
            rows.add(new Object[] {j.id(), n++, cut(ch.sheet(), 250), cut(ch.field(), 250), ch.column(), ch.ruleId(), ch.action(),
                    ch.cells(), cut(ch.rows(), 400)});
        }
        jdbc.inTransaction(c -> {
            Jdbc.update(c, "INSERT INTO cleaning_job (" + JOB + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    j.id(), j.runId(), j.kind(), cut(j.fileName(), 255), j.sha256In(), j.sizeIn(), j.sha256Out(), j.cells(),
                    j.rowsEmptied(), j.status(), j.error(), j.createdAt(), j.createdBy());
            if (!rows.isEmpty()) {
                Jdbc.batch(c, "INSERT INTO cleaning_change (job_id, line_no, sheet, field, column_ref, rule_id, action, cells, row_sample) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
            }
        });
    }

    @Override public List<CleaningJob> jobs(String runId) {
        return jdbc.query("SELECT " + JOB + " FROM cleaning_job WHERE run_id = ? ORDER BY created_at DESC, id", JdbcCleaningRepository::job, runId);
    }

    @Override public Optional<CleaningJob> job(String id) {
        return jdbc.one("SELECT " + JOB + " FROM cleaning_job WHERE id = ?", JdbcCleaningRepository::job, id);
    }

    @Override public List<CleanChange> changes(String jobId) {
        return jdbc.query("SELECT sheet, field, column_ref, rule_id, action, cells, row_sample FROM cleaning_change WHERE job_id = ? "
                + "ORDER BY line_no", rs -> new CleanChange(rs.getString("sheet"), rs.getString("field"), rs.getString("column_ref"),
                rs.getString("rule_id"), CleanAction.valueOf(rs.getString("action")), rs.getInt("cells"), rs.getString("row_sample")), jobId);
    }

    private static CleaningJob job(ResultSet rs) throws SQLException {
        return new CleaningJob(rs.getString("id"), rs.getString("run_id"), TemplateKind.valueOf(rs.getString("template_kind")),
                rs.getString("file_name"), rs.getString("sha256_in"), rs.getLong("size_in"), rs.getString("sha256_out"),
                rs.getInt("cells_changed"), rs.getInt("rows_emptied"), RunStatus.valueOf(rs.getString("status")), rs.getString("error"),
                Jdbc.instant(rs, "created_at"), rs.getString("created_by"));
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
