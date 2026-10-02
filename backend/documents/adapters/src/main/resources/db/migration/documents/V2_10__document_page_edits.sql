-- The page editor (issue #82, ADR-0024): operations that create new documents from pages of stored documents and
-- newly scanned pages, and the source relationships of the documents they create.

-- Operational record like AI runs and bulk runs: in the recovery backup, not in the portable export. The plan names
-- its source documents by id only; a source deleted later must not delete the record of the operation.
CREATE TABLE document_page_edits (
    id            UUID         PRIMARY KEY,
    requested_by  UUID         NOT NULL,
    requested_at  TIMESTAMPTZ  NOT NULL,
    plan          JSONB        NOT NULL,
    scan_path     VARCHAR(200),
    scan_checksum VARCHAR(64),
    scan_pages    INTEGER      CHECK (scan_pages > 0),
    state         VARCHAR(10)  NOT NULL CHECK (state IN ('REQUESTED', 'COMPLETED', 'FAILED')),
    attempts      INTEGER      NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    failure_code  VARCHAR(100),
    finished_at   TIMESTAMPTZ,
    results       JSONB        NOT NULL DEFAULT '[]',
    version       BIGINT       NOT NULL,
    CONSTRAINT document_page_edits_scan CHECK ((scan_path IS NULL) = (scan_checksum IS NULL)
        AND (scan_path IS NULL) = (scan_pages IS NULL)),
    CONSTRAINT document_page_edits_finished CHECK ((state = 'REQUESTED') = (finished_at IS NULL))
);

CREATE INDEX document_page_edits_requested_idx ON document_page_edits (requested_at DESC);

-- Where the pages of a created document came from; one-based page numbers. Permanently deleting the created document
-- removes its ranges; permanently deleting a source keeps the ranges and clears the source (shown as deleted).
CREATE TABLE document_derivations (
    document_id        UUID         NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    position           INTEGER      NOT NULL CHECK (position >= 0),
    first_page         INTEGER      NOT NULL CHECK (first_page >= 1),
    last_page          INTEGER      NOT NULL,
    source_kind        VARCHAR(10)  NOT NULL CHECK (source_kind IN ('DOCUMENT', 'SCAN')),
    source_document_id UUID         REFERENCES documents (id) ON DELETE SET NULL,
    first_source_page  INTEGER      NOT NULL CHECK (first_source_page >= 1),
    last_source_page   INTEGER      NOT NULL,
    rotation           INTEGER      NOT NULL CHECK (rotation IN (0, 90, 180, 270)),
    -- Not a foreign key: the portable export carries derivations but not operations.
    page_edit_id       UUID         NOT NULL,
    created_by         UUID         NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (document_id, position),
    CONSTRAINT document_derivations_pages CHECK (last_page >= first_page
        AND last_source_page - first_source_page = last_page - first_page),
    CONSTRAINT document_derivations_scan CHECK (source_kind = 'DOCUMENT' OR source_document_id IS NULL)
);

CREATE INDEX document_derivations_source_idx ON document_derivations (source_document_id);
