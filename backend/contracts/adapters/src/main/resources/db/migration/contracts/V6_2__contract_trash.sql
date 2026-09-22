-- Recoverable trash (issue #48, ADR-0010). A trashed contract keeps its row and every fact with provenance; lists,
-- published deadlines and review cards filter on trashed_at. restorable_until is the recovery promise fixed at
-- trash time.
ALTER TABLE contracts ADD COLUMN trashed_by UUID;
ALTER TABLE contracts ADD COLUMN trashed_at TIMESTAMPTZ;
ALTER TABLE contracts ADD COLUMN restorable_until TIMESTAMPTZ;

ALTER TABLE contracts ADD CONSTRAINT contracts_trash_complete CHECK (
    (trashed_at IS NULL AND trashed_by IS NULL AND restorable_until IS NULL)
    OR (trashed_at IS NOT NULL AND trashed_by IS NOT NULL AND restorable_until >= trashed_at)
);
