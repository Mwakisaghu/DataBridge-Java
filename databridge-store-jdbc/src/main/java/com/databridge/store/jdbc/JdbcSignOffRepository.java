package com.databridge.store.jdbc;

import com.databridge.core.domain.SignOff;
import com.databridge.core.port.SignOffRepository;

import javax.sql.DataSource;
import java.util.Optional;

/** JDBC store for run sign-offs. Portable SQL. */
public final class JdbcSignOffRepository implements SignOffRepository {

    private static final String COLS = "run_id, status, certificate_no, checklist_json, requested_by, requested_at, request_note, "
            + "decided_by, decided_at, decision_note";
    private final Jdbc jdbc;

    public JdbcSignOffRepository(DataSource ds) {
        this.jdbc = new Jdbc(ds);
    }

    @Override public Optional<SignOff> find(String runId) {
        return jdbc.one("SELECT " + COLS + " FROM run_signoff WHERE run_id = ?", rs -> new SignOff(rs.getString("run_id"),
                SignOff.Status.valueOf(rs.getString("status")), rs.getString("certificate_no"), rs.getString("checklist_json"),
                rs.getString("requested_by"), Jdbc.instant(rs, "requested_at"), rs.getString("request_note"), rs.getString("decided_by"),
                Jdbc.instant(rs, "decided_at"), rs.getString("decision_note")), runId);
    }

    @Override public void save(SignOff s) {
        jdbc.inTransaction(c -> {
            int n = Jdbc.update(c, "UPDATE run_signoff SET status = ?, certificate_no = ?, checklist_json = ?, requested_by = ?, "
                            + "requested_at = ?, request_note = ?, decided_by = ?, decided_at = ?, decision_note = ? WHERE run_id = ?",
                    s.status(), s.certificateNo(), s.checklistJson(), s.requestedBy(), s.requestedAt(), s.requestNote(), s.decidedBy(),
                    s.decidedAt(), s.decisionNote(), s.runId());
            if (n == 0) {
                Jdbc.update(c, "INSERT INTO run_signoff (" + COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", s.runId(), s.status(),
                        s.certificateNo(), s.checklistJson(), s.requestedBy(), s.requestedAt(), s.requestNote(), s.decidedBy(), s.decidedAt(),
                        s.decisionNote());
            }
        });
    }
}
