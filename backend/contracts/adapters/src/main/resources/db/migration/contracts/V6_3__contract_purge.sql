-- Permanent deletion from the trash (issue #85, ADR-0021). Deleting a contract row cascades to its facts. This table
-- keeps who deleted which contract and when, deliberately without its name or facts, so planning can tell a deleted
-- contract from one that never existed and the deletion stays provable without keeping the content. Member ids are
-- not foreign keys; identity owns members.
CREATE TABLE contract_purges (
    contract_id UUID        PRIMARY KEY,
    purged_by   UUID        NOT NULL,
    purged_at   TIMESTAMPTZ NOT NULL
);
