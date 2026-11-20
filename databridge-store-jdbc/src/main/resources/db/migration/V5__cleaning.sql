-- =============================================================================
-- Phase 3: cleaning and transformation.
-- cleaning_setup: stored Cleaning Setup rules overriding shipped defaults by id.
-- cleaning_job:   one clean-and-download; fingerprints only, the file is never kept.
-- cleaning_change: the change log: per rule and column, how many cells and which rows.
--                  Never a value.
-- =============================================================================

CREATE TABLE cleaning_setup (
    rule_id             VARCHAR(80)    NOT NULL,
    definition          NVARCHAR(MAX)  NOT NULL,
    modified_at         DATETIME2      NOT NULL,
    modified_by         NVARCHAR(100)  NOT NULL,
    CONSTRAINT pk_cleaning_setup PRIMARY KEY (rule_id)
);

CREATE TABLE cleaning_job (
    id                  VARCHAR(36)    NOT NULL,
    run_id              VARCHAR(36)    NOT NULL,
    template_kind       VARCHAR(20)    NOT NULL,
    file_name           NVARCHAR(255)  NOT NULL,
    sha256_in           VARCHAR(64)    NOT NULL,
    size_in             BIGINT         NOT NULL,
    sha256_out          VARCHAR(64)    NULL,
    cells_changed       INT            NOT NULL,
    rows_emptied        INT            NOT NULL,
    status              VARCHAR(10)    NOT NULL,
    error               NVARCHAR(1000) NULL,
    created_at          DATETIME2      NOT NULL,
    created_by          NVARCHAR(100)  NOT NULL,
    CONSTRAINT pk_cleaning_job PRIMARY KEY (id),
    CONSTRAINT fk_cleaning_job_run FOREIGN KEY (run_id) REFERENCES migration_run (id)
);

CREATE INDEX ix_cleaning_job_run ON cleaning_job (run_id, created_at);

CREATE TABLE cleaning_change (
    job_id              VARCHAR(36)    NOT NULL,
    line_no             INT            NOT NULL,
    sheet               NVARCHAR(250)  NOT NULL,
    field               NVARCHAR(250)  NULL,
    column_ref          VARCHAR(10)    NULL,
    rule_id             VARCHAR(80)    NOT NULL,
    action              VARCHAR(30)    NOT NULL,
    cells               INT            NOT NULL,
    row_sample          VARCHAR(400)   NULL,
    CONSTRAINT pk_cleaning_change PRIMARY KEY (job_id, line_no),
    CONSTRAINT fk_cleaning_change_job FOREIGN KEY (job_id) REFERENCES cleaning_job (id)
);
