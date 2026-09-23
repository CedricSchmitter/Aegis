-- Recoverable trash (issue #48, ADR-0010). A trashed document keeps its row, original, archive link, metadata and
-- relationships; ordinary views filter on trashed_at. restorable_until is the recovery promise fixed at trash time.
-- The checksum stays unique, so the same bytes uploaded again resolve to the trashed document instead of a copy.
ALTER TABLE documents ADD COLUMN trashed_by UUID;
ALTER TABLE documents ADD COLUMN trashed_at TIMESTAMPTZ;
ALTER TABLE documents ADD COLUMN restorable_until TIMESTAMPTZ;

ALTER TABLE documents ADD CONSTRAINT documents_trash_complete CHECK (
    (trashed_at IS NULL AND trashed_by IS NULL AND restorable_until IS NULL)
    OR (trashed_at IS NOT NULL AND trashed_by IS NOT NULL AND restorable_until >= trashed_at)
);

CREATE INDEX documents_trashed_at_idx ON documents (trashed_at DESC) WHERE trashed_at IS NOT NULL;
