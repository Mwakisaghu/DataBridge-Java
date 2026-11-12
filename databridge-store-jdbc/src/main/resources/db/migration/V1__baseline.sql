-- =============================================================================
-- DataBridge baseline schema (SQL Server; also runs on H2 in MSSQLServer mode).
-- These tables hold setups, structure, metadata and audit only. Client data
-- (member names, IDs, balances) is never stored here: see docs/adr/0001.
-- =============================================================================

-- DataBridge Setup and Retention Setup: one row, like a BC setup table.
CREATE TABLE databridge_setup (
    setup_key           VARCHAR(10)    NOT NULL,
    workspace_dir       NVARCHAR(400)  NOT NULL,
    retention_mode      VARCHAR(30)    NOT NULL,
    keep_hours          INT            NOT NULL,
    max_run_hours       INT            NOT NULL,
    secure_overwrite    BIT            NOT NULL,
    sweep_on_startup    BIT            NOT NULL,
    finding_value_mode  VARCHAR(10)    NOT NULL,
    caption_scan_rows   INT            NOT NULL,
    max_upload_mb       INT            NOT NULL,
    modified_at         DATETIME2      NOT NULL,
    modified_by         NVARCHAR(100)  NOT NULL,
    CONSTRAINT pk_databridge_setup PRIMARY KEY (setup_key)
);

-- Template Setup: header per sheet, lines per column.
CREATE TABLE template_setup_sheet (
    sheet_key           NVARCHAR(250)  NOT NULL,
    sheet_name          NVARCHAR(250)  NOT NULL,
    table_name          NVARCHAR(250)  NULL,
    table_id            INT            NULL,
    caption_row         INT            NULL,
    purpose             NVARCHAR(250)  NULL,
    modified_at         DATETIME2      NOT NULL,
    modified_by         NVARCHAR(100)  NOT NULL,
    CONSTRAINT pk_template_setup_sheet PRIMARY KEY (sheet_key)
);

CREATE TABLE template_setup_field (
    sheet_key           NVARCHAR(250)  NOT NULL,
    line_no             INT            NOT NULL,
    caption             NVARCHAR(250)  NOT NULL,
    field_type          VARCHAR(30)    NOT NULL,
    options             NVARCHAR(MAX)  NULL,
    required            BIT            NOT NULL,
    CONSTRAINT pk_template_setup_field PRIMARY KEY (sheet_key, line_no),
    CONSTRAINT fk_template_setup_field_sheet FOREIGN KEY (sheet_key)
        REFERENCES template_setup_sheet (sheet_key) ON DELETE CASCADE
);

CREATE TABLE client (
    id                  VARCHAR(36)    NOT NULL,
    code                VARCHAR(20)    NOT NULL,
    name                NVARCHAR(100)  NOT NULL,
    created_at          DATETIME2      NOT NULL,
    created_by          NVARCHAR(100)  NOT NULL,
    CONSTRAINT pk_client PRIMARY KEY (id),
    CONSTRAINT uq_client_code UNIQUE (code)
);

CREATE TABLE project (
    id                  VARCHAR(36)    NOT NULL,
    client_id           VARCHAR(36)    NOT NULL,
    code                VARCHAR(20)    NOT NULL,
    name                NVARCHAR(100)  NOT NULL,
    bc_company          NVARCHAR(100)  NULL,
    created_at          DATETIME2      NOT NULL,
    created_by          NVARCHAR(100)  NOT NULL,
    CONSTRAINT pk_project PRIMARY KEY (id),
    CONSTRAINT uq_project_code UNIQUE (client_id, code),
    CONSTRAINT fk_project_client FOREIGN KEY (client_id) REFERENCES client (id)
);

CREATE TABLE migration_run (
    id                  VARCHAR(36)    NOT NULL,
    project_id          VARCHAR(36)    NOT NULL,
    run_no              INT            NOT NULL,
    run_type            VARCHAR(10)    NOT NULL,
    cutoff_date         DATE           NOT NULL,
    state               VARCHAR(10)    NOT NULL,
    created_at          DATETIME2      NOT NULL,
    created_by          NVARCHAR(100)  NOT NULL,
    CONSTRAINT pk_migration_run PRIMARY KEY (id),
    CONSTRAINT uq_migration_run_no UNIQUE (project_id, run_no),
    CONSTRAINT fk_migration_run_project FOREIGN KEY (project_id) REFERENCES project (id)
);

-- One row per uploaded workbook: metadata, SHA-256 and schema (structure) only.
CREATE TABLE dataset_version (
    id                  VARCHAR(36)    NOT NULL,
    run_id              VARCHAR(36)    NOT NULL,
    template_kind       VARCHAR(20)    NOT NULL,
    version_no          INT            NOT NULL,
    file_name           NVARCHAR(260)  NOT NULL,
    sha256              CHAR(64)       NULL,
    size_bytes          BIGINT         NOT NULL,
    sheet_count         INT            NOT NULL,
    warning_count       INT            NOT NULL,
    status              VARCHAR(10)    NOT NULL,
    error               NVARCHAR(1000) NULL,
    schema_json         NVARCHAR(MAX)  NULL,
    uploaded_at         DATETIME2      NOT NULL,
    uploaded_by         NVARCHAR(100)  NOT NULL,
    CONSTRAINT pk_dataset_version PRIMARY KEY (id),
    CONSTRAINT uq_dataset_version_no UNIQUE (run_id, template_kind, version_no),
    CONSTRAINT fk_dataset_version_run FOREIGN KEY (run_id) REFERENCES migration_run (id)
);

-- Append-only audit trail. Details never carry client values.
CREATE TABLE audit_event (
    id                  VARCHAR(36)    NOT NULL,
    occurred_at         DATETIME2      NOT NULL,
    actor               NVARCHAR(100)  NOT NULL,
    action              VARCHAR(50)    NOT NULL,
    entity_type         VARCHAR(50)    NOT NULL,
    entity_id           NVARCHAR(250)  NULL,
    details             NVARCHAR(2000) NULL,
    CONSTRAINT pk_audit_event PRIMARY KEY (id)
);

CREATE INDEX ix_audit_event_entity ON audit_event (entity_type, entity_id);
CREATE INDEX ix_audit_event_time ON audit_event (occurred_at);
