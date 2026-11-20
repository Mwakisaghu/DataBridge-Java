package com.databridge.store.jdbc;

import com.databridge.core.port.ReconRepository;
import com.databridge.core.recon.ControlDef;
import com.databridge.core.recon.ReconLine;
import com.databridge.core.recon.TrialBalanceDef;
import com.databridge.core.setup.SetupLoader;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** JDBC store for the Reconciliation Setup and reconciliation lines. Portable SQL. */
public final class JdbcReconRepository implements ReconRepository {

    private static final String CONTROL = "CONTROL";
    private static final String TB = "TB";
    private final Jdbc jdbc;

    public JdbcReconRepository(DataSource ds) {
        this.jdbc = new Jdbc(ds);
    }

    @Override public List<ControlDef> controls() {
        return jdbc.query("SELECT definition FROM recon_setup WHERE kind = ? ORDER BY setup_id",
                rs -> read(rs, ControlDef.class), CONTROL);
    }

    @Override public void saveControl(ControlDef c, String actor, Instant at) {
        save(c.getId(), CONTROL, json(c), actor, at);
    }

    @Override public boolean deleteControl(String id) {
        return jdbc.update("DELETE FROM recon_setup WHERE setup_id = ? AND kind = ?", id, CONTROL) > 0;
    }

    @Override public Optional<TrialBalanceDef> trialBalance() {
        return jdbc.one("SELECT definition FROM recon_setup WHERE setup_id = ? AND kind = ?",
                rs -> read(rs, TrialBalanceDef.class), TRIAL_BALANCE_ID, TB);
    }

    @Override public void saveTrialBalance(TrialBalanceDef tb, String actor, Instant at) {
        save(TRIAL_BALANCE_ID, TB, json(tb), actor, at);
    }

    @Override public boolean deleteTrialBalance() {
        return jdbc.update("DELETE FROM recon_setup WHERE setup_id = ? AND kind = ?", TRIAL_BALANCE_ID, TB) > 0;
    }

    private static final String COLS = "check_id, line_no, run_id, line_type, control_id, gl_account, name, left_amount, "
            + "right_amount, variance, status, sources, note";

    @Override public void insertLines(String checkId, String runId, List<ReconLine> lines) {
        if (lines.isEmpty()) return;
        List<Object[]> rows = new ArrayList<>();
        for (ReconLine l : lines) {
            rows.add(new Object[] {checkId, l.lineNo(), runId, l.type(), l.controlId(), cut(l.glAccount(), 40), cut(l.name(), 250),
                    l.left(), l.right(), l.variance(), l.status(), cut(l.sources(), 1000), cut(l.note(), 500)});
        }
        jdbc.inTransaction(c -> Jdbc.batch(c, "INSERT INTO recon_line (" + COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows));
    }

    @Override public List<ReconLine> lines(String checkId) {
        return jdbc.query("SELECT " + COLS + " FROM recon_line WHERE check_id = ? ORDER BY line_no", rs -> new ReconLine(
                rs.getInt("line_no"), ReconLine.Type.valueOf(rs.getString("line_type")), rs.getString("control_id"),
                rs.getString("gl_account"), rs.getString("name"), rs.getBigDecimal("left_amount"), rs.getBigDecimal("right_amount"),
                rs.getBigDecimal("variance"), ReconLine.Status.valueOf(rs.getString("status")), rs.getString("sources"),
                rs.getString("note")), checkId);
    }

    private void save(String id, String kind, String json, String actor, Instant at) {
        jdbc.inTransaction(c -> {
            int n = Jdbc.update(c, "UPDATE recon_setup SET definition = ?, modified_at = ?, modified_by = ? WHERE setup_id = ? AND kind = ?",
                    json, at, actor, id, kind);
            if (n == 0) {
                Jdbc.update(c, "INSERT INTO recon_setup (setup_id, kind, definition, modified_at, modified_by) VALUES (?, ?, ?, ?, ?)",
                        id, kind, json, at, actor);
            }
        });
    }

    private static String json(Object o) {
        try {
            return SetupLoader.mapper().writeValueAsString(o);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> T read(ResultSet rs, Class<T> type) throws SQLException {
        try {
            return SetupLoader.mapper().readValue(rs.getString("definition"), type);
        } catch (IOException e) {
            throw new SQLException("Stored reconciliation setup cannot be read: " + e.getMessage(), e);
        }
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
