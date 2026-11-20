-- =============================================================================
-- Phase 1: validation checks, the exception register and the Rule Setup.
-- Findings hold masked values only (see docs/adr/0001 and 0005).
-- =============================================================================

CREATE TABLE validation_check (
    id                  VARCHAR(36)    NOT NULL,
    run_id              VARCHAR(36)    NOT NULL,
    check_no            INT            NOT NULL,
    status              VARCHAR(10)    NOT NULL,
    dataset_ids         NVARCHAR(400)  NOT NULL,
    rows_checked        INT            NOT NULL,
    critical_count      INT            NOT NULL,
    warning_count       INT            NOT NULL,
    info_count          INT            NOT NULL,
    readiness           DECIMAL(5,4)   NOT NULL,
    sheets_json         NVARCHAR(MAX)  NULL,
    skipped_json        NVARCHAR(MAX)  NULL,
    error               NVARCHAR(1000) NULL,
    created_at          DATETIME2      NOT NULL,
    created_by          NVARCHAR(100)  NOT NULL,
    CONSTRAINT pk_validation_check PRIMARY KEY (id),
    CONSTRAINT uq_validation_check_no UNIQUE (run_id, check_no),
    CONSTRAINT fk_validation_check_run FOREIGN KEY (run_id) REFERENCES migration_run (id)
);

CREATE TABLE finding (
    id                  VARCHAR(36)    NOT NULL,
    check_id            VARCHAR(36)    NOT NULL,
    run_id              VARCHAR(36)    NOT NULL,
    template_kind       VARCHAR(20)    NULL,
    sheet               NVARCHAR(250)  NULL,
    row_no              INT            NOT NULL,
    column_ref          VARCHAR(10)    NULL,
    field               NVARCHAR(250)  NULL,
    rule_id             VARCHAR(80)    NOT NULL,
    rule_type           VARCHAR(20)    NOT NULL,
    severity            VARCHAR(10)    NOT NULL,
    severity_rank       INT            NOT NULL,
    message             NVARCHAR(500)  NOT NULL,
    value_masked        NVARCHAR(300)  NULL,
    auto_fixable        BIT            NOT NULL,
    status              VARCHAR(20)    NOT NULL,
    waiver_reason       NVARCHAR(500)  NULL,
    requested_by        NVARCHAR(100)  NULL,
    requested_at        DATETIME2      NULL,
    decided_by          NVARCHAR(100)  NULL,
    decided_at          DATETIME2      NULL,
    decision_note       NVARCHAR(500)  NULL,
    CONSTRAINT pk_finding PRIMARY KEY (id),
    CONSTRAINT fk_finding_check FOREIGN KEY (check_id) REFERENCES validation_check (id)
);

CREATE INDEX ix_finding_check ON finding (check_id, severity_rank, sheet, row_no);
CREATE INDEX ix_finding_run ON finding (run_id);

CREATE TABLE rule_setup (
    rule_id             VARCHAR(80)    NOT NULL,
    definition          NVARCHAR(MAX)  NOT NULL,
    modified_at         DATETIME2      NOT NULL,
    modified_by         NVARCHAR(100)  NOT NULL,
    CONSTRAINT pk_rule_setup PRIMARY KEY (rule_id)
);
