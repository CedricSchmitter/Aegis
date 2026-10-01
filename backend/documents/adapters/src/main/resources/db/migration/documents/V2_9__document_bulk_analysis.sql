-- Explicitly started classification of existing documents (issue #115, ADR-0022). A bulk run fixes its document
-- list when a member starts it and requests the documents' classification runs in bounded batches. Run progress and
-- cost stay in the assistant context (ai_runs); items reference a document's analysis request only by its generation.

ALTER TABLE document_analyses DROP CONSTRAINT document_analyses_trigger_check;
ALTER TABLE document_analyses ADD CONSTRAINT document_analyses_trigger_check
    CHECK (trigger IN ('UPLOAD', 'MANUAL', 'BULK'));

CREATE TABLE document_bulk_analyses (
    id                 UUID         PRIMARY KEY,
    requested_by       UUID         NOT NULL,
    requested_at       TIMESTAMPTZ  NOT NULL,
    scope_kind         VARCHAR(10)  NOT NULL CHECK (scope_kind IN ('ALL', 'UNSORTED', 'FOLDER')),
    -- Not a foreign key: a folder deleted later must not delete the record of the run.
    scope_folder_id    UUID,
    include_subfolders BOOLEAN      NOT NULL DEFAULT false,
    batch_size         INTEGER      NOT NULL CHECK (batch_size BETWEEN 1 AND 10),
    state              VARCHAR(10)  NOT NULL CHECK (state IN ('RUNNING', 'COMPLETED', 'CANCELLED', 'STOPPED')),
    stop_code          VARCHAR(100),
    cancelled_by       UUID,
    cancelled_at       TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ,
    version            BIGINT       NOT NULL,
    CONSTRAINT document_bulk_analyses_scope CHECK ((scope_kind = 'FOLDER') = (scope_folder_id IS NOT NULL)),
    CONSTRAINT document_bulk_analyses_finished CHECK ((state = 'RUNNING') = (finished_at IS NULL)),
    CONSTRAINT document_bulk_analyses_cancelled CHECK ((state = 'CANCELLED') = (cancelled_at IS NOT NULL))
);

-- One bulk run at a time: a second start, even a concurrent one, fails on this index.
CREATE UNIQUE INDEX document_bulk_analyses_one_running ON document_bulk_analyses ((true)) WHERE state = 'RUNNING';
CREATE INDEX document_bulk_analyses_requested_idx ON document_bulk_analyses (requested_at DESC);

-- A document deleted permanently leaves the run with it (ADR-0021).
CREATE TABLE document_bulk_analysis_items (
    bulk_id     UUID         NOT NULL REFERENCES document_bulk_analyses (id) ON DELETE CASCADE,
    document_id UUID         NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    position    INTEGER      NOT NULL CHECK (position >= 0),
    state       VARCHAR(10)  NOT NULL
        CHECK (state IN ('PENDING', 'REQUESTED', 'FINISHED', 'SKIPPED', 'CANCELLED')),
    generation  INTEGER      NOT NULL DEFAULT 0 CHECK (generation >= 0),
    outcome     VARCHAR(20)  CHECK (outcome IN ('CLASSIFIED', 'FAILED', 'NOT_CLASSIFIED', 'SUPERSEDED')),
    code        VARCHAR(100),
    updated_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (bulk_id, document_id),
    CONSTRAINT document_bulk_analysis_items_outcome CHECK ((state = 'FINISHED') = (outcome IS NOT NULL))
);

CREATE INDEX document_bulk_analysis_items_document_idx ON document_bulk_analysis_items (document_id);
