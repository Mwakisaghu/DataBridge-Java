-- =============================================================================
-- Phase 2: financial reconciliation.
-- recon_setup: stored Reconciliation Setup entries (the trial balance layout, kind TB, and
--   control accounts, kind CONTROL) overriding the shipped defaults with the same id.
-- recon_line: per check, the trial balance totals and each control account's GL balance,
--   subledger total and variance. Account-level totals only; no record-level client data.
-- =============================================================================

CREATE TABLE recon_setup (
    setup_id            VARCHAR(80)    NOT NULL,
    kind                VARCHAR(10)    NOT NULL,
    definition          NVARCHAR(MAX)  NOT NULL,
    modified_at         DATETIME2      NOT NULL,
    modified_by         NVARCHAR(100)  NOT NULL,
    CONSTRAINT pk_recon_setup PRIMARY KEY (setup_id)
);

CREATE TABLE recon_line (
    check_id            VARCHAR(36)    NOT NULL,
    line_no             INT            NOT NULL,
    run_id              VARCHAR(36)    NOT NULL,
    line_type           VARCHAR(10)    NOT NULL,
    control_id          VARCHAR(80)    NULL,
    gl_account          NVARCHAR(40)   NULL,
    name                NVARCHAR(250)  NULL,
    left_amount         DECIMAL(19,2)  NULL,
    right_amount        DECIMAL(19,2)  NULL,
    variance            DECIMAL(19,2)  NULL,
    status              VARCHAR(12)    NOT NULL,
    sources             NVARCHAR(1000) NULL,
    note                NVARCHAR(500)  NULL,
    CONSTRAINT pk_recon_line PRIMARY KEY (check_id, line_no),
    CONSTRAINT fk_recon_line_check FOREIGN KEY (check_id) REFERENCES validation_check (id)
);
