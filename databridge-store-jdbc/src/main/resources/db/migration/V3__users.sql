-- =============================================================================
-- Sign-in: local accounts and sessions. Passwords are PBKDF2 hashes; sessions
-- store only the SHA-256 of their token.
-- =============================================================================

CREATE TABLE app_user (
    id                  VARCHAR(36)    NOT NULL,
    username            VARCHAR(60)    NOT NULL,
    display_name        NVARCHAR(100)  NOT NULL,
    password_hash       VARCHAR(200)   NOT NULL,
    roles               VARCHAR(100)   NOT NULL,
    active              BIT            NOT NULL,
    must_change_password BIT           NOT NULL,
    failed_attempts     INT            NOT NULL,
    locked_until        DATETIME2      NULL,
    last_login_at       DATETIME2      NULL,
    created_at          DATETIME2      NOT NULL,
    created_by          NVARCHAR(100)  NOT NULL,
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT uq_app_user_username UNIQUE (username)
);

CREATE TABLE app_session (
    token_hash          CHAR(64)       NOT NULL,
    user_id             VARCHAR(36)    NOT NULL,
    created_at          DATETIME2      NOT NULL,
    expires_at          DATETIME2      NOT NULL,
    last_seen_at        DATETIME2      NOT NULL,
    CONSTRAINT pk_app_session PRIMARY KEY (token_hash),
    CONSTRAINT fk_app_session_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);

CREATE INDEX ix_app_session_user ON app_session (user_id);
