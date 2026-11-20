-- =============================================================================
-- Phase 5: run sign-off (maker-checker) and migration certificate.
-- =============================================================================

CREATE TABLE run_signoff (
    run_id              VARCHAR(36)    NOT NULL,
    status              VARCHAR(10)    NOT NULL,
    certificate_no      VARCHAR(80)    NULL,
    checklist_json      NVARCHAR(MAX)  NOT NULL,
    requested_by        NVARCHAR(100)  NOT NULL,
    requested_at        DATETIME2      NOT NULL,
    request_note        NVARCHAR(500)  NULL,
    decided_by          NVARCHAR(100)  NULL,
    decided_at          DATETIME2      NULL,
    decision_note       NVARCHAR(500)  NULL,
    CONSTRAINT pk_run_signoff PRIMARY KEY (run_id),
    CONSTRAINT fk_run_signoff_run FOREIGN KEY (run_id) REFERENCES migration_run (id)
);
