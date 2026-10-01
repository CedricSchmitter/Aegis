-- Permanent deletion from the trash (issue #85, ADR-0021). This table keeps who deleted which task and when,
-- deliberately without its title or note. Planning also reads it so an automatic cancellation review a member deleted
-- is not created again under its stable id. Member ids are not foreign keys; identity owns members.
CREATE TABLE task_purges (
    task_id   UUID        PRIMARY KEY,
    purged_by UUID        NOT NULL,
    purged_at TIMESTAMPTZ NOT NULL
);

-- A completed cancellation review stays as history when its contract is deleted permanently; this marks that its
-- contract no longer exists, so the review shows it instead of linking to nothing.
ALTER TABLE tasks ADD COLUMN contract_deleted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE tasks ADD CONSTRAINT tasks_contract_deleted_review CHECK (NOT contract_deleted OR contract_id IS NOT NULL);
