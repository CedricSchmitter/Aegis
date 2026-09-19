-- Automatic document analysis (issue #23, ADR-0004): title provenance and the per-document analysis record.

-- Where the display title came from. Existing titles count as chosen by a member unless they are still exactly the
-- name derived from the uploaded filename (DocumentCaptureService.titleFrom), so no existing member title is ever
-- replaced automatically.
ALTER TABLE documents ADD COLUMN title_origin VARCHAR(20) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE documents ADD CONSTRAINT documents_title_origin_check
    CHECK (title_origin IN ('FILENAME', 'MANUAL', 'AUTOMATIC'));
UPDATE documents SET title_origin = 'FILENAME'
 WHERE lower(original_filename) LIKE '%.pdf'
   AND title = btrim(replace(left(original_filename, length(original_filename) - 4), '_', ' '));

-- One row per analyzed (or upload-marked) document. Existing documents get no row: they are only analyzed when a
-- member selects them. Run progress lives in the assistant context (ai_runs); this table references a run only by
-- its source reference, not by a foreign key, because contexts do not share tables.
CREATE TABLE document_analyses (
    document_id             UUID         PRIMARY KEY REFERENCES documents (id) ON DELETE CASCADE,
    status                  VARCHAR(20)  NOT NULL
        CHECK (status IN ('NOT_REQUESTED', 'AWAITING_ARCHIVE', 'REQUESTED', 'UNAVAILABLE', 'COMPLETED')),
    generation              INTEGER      NOT NULL DEFAULT 0 CHECK (generation >= 0),
    trigger                 VARCHAR(10)  CHECK (trigger IN ('UPLOAD', 'MANUAL')),
    requested_by            UUID,
    requested_at            TIMESTAMPTZ,
    unavailable_code        VARCHAR(100),
    completed_at            TIMESTAMPTZ,
    completed_run_ref       VARCHAR(200),
    model                   VARCHAR(100),
    applied_labels          INTEGER      NOT NULL DEFAULT 0,
    suggested_labels        INTEGER      NOT NULL DEFAULT 0,
    title                   VARCHAR(200),
    title_decision          VARCHAR(20)  CHECK (title_decision IN ('APPLIED', 'SUGGESTED', 'ACCEPTED', 'REJECTED')),
    title_before            VARCHAR(200),
    document_date           DATE,
    document_date_evidence  VARCHAR(250),
    document_date_status    VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN'
        CHECK (document_date_status IN ('FOUND', 'CONFLICTING', 'UNVERIFIED', 'UNKNOWN')),
    short_topic             VARCHAR(60),
    correspondent_label_id  UUID,
    flags                   JSONB        NOT NULL DEFAULT '[]'::jsonb,
    candidates              JSONB        NOT NULL DEFAULT '[]'::jsonb,
    subjects                JSONB        NOT NULL DEFAULT '[]'::jsonb,
    history                 JSONB        NOT NULL DEFAULT '[]'::jsonb,
    filename_override       VARCHAR(200),
    filename_by             UUID,
    filename_at             TIMESTAMPTZ,
    version                 BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX document_analyses_status_idx ON document_analyses (status);
