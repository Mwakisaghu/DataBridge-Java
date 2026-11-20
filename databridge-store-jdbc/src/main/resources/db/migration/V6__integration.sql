-- =============================================================================
-- Phase 4: migration and integration hub.
-- integration_setup: the stored Integration Setup (no secrets: the client secret
--   stays in a server environment variable).
-- package_job: import packages built; fingerprints and table list, never the file.
-- import_record: per run and sheet, rows packaged, rows imported, count read back
--   from Business Central.
-- =============================================================================

CREATE TABLE integration_setup (
    setup_id            VARCHAR(40)    NOT NULL,
    definition          NVARCHAR(MAX)  NOT NULL,
    modified_at         DATETIME2      NOT NULL,
    modified_by         NVARCHAR(100)  NOT NULL,
    CONSTRAINT pk_integration_setup PRIMARY KEY (setup_id)
);

CREATE TABLE package_job (
    id                  VARCHAR(36)    NOT NULL,
    run_id              VARCHAR(36)    NOT NULL,
    check_id            VARCHAR(36)    NULL,
    template_kind       VARCHAR(20)    NOT NULL,
    file_name           NVARCHAR(255)  NOT NULL,
    sha256_in           VARCHAR(64)    NOT NULL,
    sha256_out          VARCHAR(64)    NOT NULL,
    tables_json         NVARCHAR(MAX)  NOT NULL,
    created_at          DATETIME2      NOT NULL,
    created_by          NVARCHAR(100)  NOT NULL,
    CONSTRAINT pk_package_job PRIMARY KEY (id),
    CONSTRAINT fk_package_job_run FOREIGN KEY (run_id) REFERENCES migration_run (id)
);

CREATE INDEX ix_package_job_run ON package_job (run_id, created_at);

CREATE TABLE import_record (
    run_id              VARCHAR(36)    NOT NULL,
    sheet               NVARCHAR(250)  NOT NULL,
    table_id            INT            NULL,
    expected_rows       INT            NOT NULL,
    imported_rows       INT            NULL,
    bc_count            BIGINT         NULL,
    status              VARCHAR(10)    NOT NULL,
    note                NVARCHAR(500)  NULL,
    updated_at          DATETIME2      NOT NULL,
    updated_by          NVARCHAR(100)  NOT NULL,
    CONSTRAINT pk_import_record PRIMARY KEY (run_id, sheet),
    CONSTRAINT fk_import_record_run FOREIGN KEY (run_id) REFERENCES migration_run (id)
);
