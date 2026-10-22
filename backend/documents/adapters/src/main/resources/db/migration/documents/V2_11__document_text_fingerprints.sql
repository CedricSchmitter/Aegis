-- Fingerprints of the archive text for near-duplicate hints (issue #42, ADR-0028): hashes of word sequences and of
-- numbers, never the text. The first byte is the fingerprint version. Measured checks stored before this column
-- existed keep their count and archive version; the text check sweep reads them again in its bounded batches after
-- every other due document, and until then they are not compared.
ALTER TABLE document_text_checks ADD COLUMN text_fingerprint BYTEA;

ALTER TABLE document_text_checks
    ADD CONSTRAINT document_text_checks_fingerprint CHECK (text_fingerprint IS NULL OR archive_version IS NOT NULL);
