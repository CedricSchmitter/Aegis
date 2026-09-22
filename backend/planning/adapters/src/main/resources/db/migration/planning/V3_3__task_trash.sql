-- Recoverable trash (issue #48, ADR-0010). A trashed task keeps its row, status, dates, owner and source link;
-- lists, Today and reminders filter on trashed_at. restorable_until is the recovery promise fixed at trash time.
ALTER TABLE tasks ADD COLUMN trashed_by UUID;
ALTER TABLE tasks ADD COLUMN trashed_at TIMESTAMPTZ;
ALTER TABLE tasks ADD COLUMN restorable_until TIMESTAMPTZ;

ALTER TABLE tasks ADD CONSTRAINT tasks_trash_complete CHECK (
    (trashed_at IS NULL AND trashed_by IS NULL AND restorable_until IS NULL)
    OR (trashed_at IS NOT NULL AND trashed_by IS NOT NULL AND restorable_until >= trashed_at)
);

CREATE INDEX tasks_trashed_at_idx ON tasks (trashed_at DESC) WHERE trashed_at IS NOT NULL;
