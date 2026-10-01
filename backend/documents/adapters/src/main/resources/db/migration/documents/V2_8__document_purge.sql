-- Permanent deletion from the trash (issue #85, ADR-0021). A member's request is stored on the trashed document until
-- the purge job has removed the archive original, its Paperless trash entry and the staging copy; then the row and
-- everything that cascades from it is deleted. purge_failure_code/purge_failed_at mark a purge the job gave up on.
ALTER TABLE documents ADD COLUMN purge_requested_by UUID;
ALTER TABLE documents ADD COLUMN purge_requested_at TIMESTAMPTZ;
ALTER TABLE documents ADD COLUMN purge_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE documents ADD COLUMN purge_failure_code VARCHAR(100);
ALTER TABLE documents ADD COLUMN purge_failed_at TIMESTAMPTZ;

ALTER TABLE documents ADD CONSTRAINT documents_purge_complete CHECK (
    (purge_requested_at IS NULL AND purge_requested_by IS NULL AND purge_failure_code IS NULL
        AND purge_failed_at IS NULL AND purge_attempts = 0)
    OR (purge_requested_at IS NOT NULL AND purge_requested_by IS NOT NULL AND trashed_at IS NOT NULL
        AND purge_attempts >= 0 AND (purge_failure_code IS NULL) = (purge_failed_at IS NULL))
);

CREATE INDEX documents_purge_pending_idx ON documents (purge_requested_at)
    WHERE purge_requested_at IS NOT NULL AND purge_failed_at IS NULL;

-- Who deleted which document permanently and when. Deliberately without title, filename or checksum: the record
-- proves the deletion without keeping the content. Member ids are not foreign keys; identity owns members.
CREATE TABLE document_purges (
    document_id  UUID        PRIMARY KEY,
    requested_by UUID        NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    purged_at    TIMESTAMPTZ NOT NULL
);
