-- Whether the archive keeps a searchable archive version of an archived document (ADR-0016): Paperless names it in
-- archived_file_name for photos and for PDFs without a text layer of their own, such as scanned pages. The text check
-- reads it together with the OCR text. Checks stored before this column existed keep their count and become due at
-- once, so the text check sweep reads them again in its bounded batches; until then they offer no archive version.
ALTER TABLE document_text_checks ADD COLUMN archive_version BOOLEAN;

ALTER TABLE document_text_checks DROP CONSTRAINT document_text_checks_outcome;

UPDATE document_text_checks SET next_attempt_at = checked_at WHERE next_attempt_at IS NULL;

ALTER TABLE document_text_checks
    ADD CONSTRAINT document_text_checks_outcome CHECK (
        (archive_version IS NOT NULL AND recognized_characters IS NOT NULL AND next_attempt_at IS NULL)
        OR (archive_version IS NULL AND next_attempt_at IS NOT NULL)
    ),
    ADD CONSTRAINT document_text_checks_characters CHECK (recognized_characters IS NULL OR recognized_characters >= 0);

DROP INDEX document_text_checks_retry_idx;

CREATE INDEX document_text_checks_retry_idx ON document_text_checks (next_attempt_at)
    WHERE archive_version IS NULL;
