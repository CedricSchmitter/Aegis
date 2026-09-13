-- Documents context: capture records, processing state and subject metadata.
CREATE TABLE documents (
    id                   UUID PRIMARY KEY,
    title                VARCHAR(200) NOT NULL,
    original_filename    VARCHAR(255) NOT NULL,
    content_type         VARCHAR(100) NOT NULL,
    size_bytes           BIGINT       NOT NULL,
    checksum_sha256      VARCHAR(64)  NOT NULL,
    uploaded_by          UUID         NOT NULL,
    uploaded_at          TIMESTAMPTZ  NOT NULL,
    staging_path         VARCHAR(255) NOT NULL,
    staging_present      BOOLEAN      NOT NULL DEFAULT TRUE,
    state                VARCHAR(20)  NOT NULL,
    attempts             INTEGER      NOT NULL DEFAULT 0,
    last_failure_code    VARCHAR(100),
    last_failure_message VARCHAR(500),
    last_failure_at      TIMESTAMPTZ,
    paperless_task_id    VARCHAR(64),
    paperless_document_id INTEGER,
    submitted_at         TIMESTAMPTZ,
    archived_at          TIMESTAMPTZ,
    version              BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT documents_checksum_unique UNIQUE (checksum_sha256),
    CONSTRAINT documents_state_check CHECK (state IN ('RECEIVED', 'SUBMITTED', 'ARCHIVED', 'FAILED'))
);

CREATE INDEX documents_state_idx ON documents (state);
CREATE INDEX documents_uploaded_at_idx ON documents (uploaded_at DESC);
CREATE INDEX documents_paperless_id_idx ON documents (paperless_document_id);

CREATE TABLE document_subjects (
    document_id UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    kind        VARCHAR(20) NOT NULL,
    member_id   UUID,
    CONSTRAINT document_subjects_unique UNIQUE NULLS NOT DISTINCT (document_id, kind, member_id),
    CONSTRAINT document_subjects_kind_check CHECK (kind IN ('MEMBER', 'HOUSE', 'HOUSEHOLD'))
);
