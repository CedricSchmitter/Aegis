-- Whether the archive recognized text in an archived document (issue #87, ADR-0012). Derived data: measured from
-- the OCR text of one archive document (only the number of letters and digits is kept), never exported, and
-- measured again by the text check sweep when it is missing or belongs to another archive document.
CREATE TABLE document_text_checks (
    document_id           UUID PRIMARY KEY REFERENCES documents (id) ON DELETE CASCADE,
    paperless_document_id INTEGER     NOT NULL,
    recognized_characters INTEGER,
    checked_at            TIMESTAMPTZ NOT NULL,
    failed_attempts       INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at       TIMESTAMPTZ,
    CONSTRAINT document_text_checks_outcome CHECK (
        (recognized_characters IS NOT NULL AND recognized_characters >= 0 AND next_attempt_at IS NULL)
        OR (recognized_characters IS NULL AND next_attempt_at IS NOT NULL)
    ),
    CONSTRAINT document_text_checks_attempts CHECK (failed_attempts >= 0)
);

CREATE INDEX document_text_checks_retry_idx ON document_text_checks (next_attempt_at)
    WHERE recognized_characters IS NULL;
