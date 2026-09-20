-- Planning-owned projection of one confirmed contractual deadline occurrence.
ALTER TABLE tasks DROP CONSTRAINT tasks_status_check;
ALTER TABLE tasks ADD CONSTRAINT tasks_status_check CHECK (status IN ('OPEN', 'DONE', 'CANCELLED'));

ALTER TABLE tasks ADD COLUMN automation_kind VARCHAR(40);
ALTER TABLE tasks ADD COLUMN contract_id UUID;
ALTER TABLE tasks ADD COLUMN contract_name VARCHAR(200);
ALTER TABLE tasks ADD COLUMN contract_end DATE;
ALTER TABLE tasks ADD COLUMN cancellation_deadline DATE;
ALTER TABLE tasks ADD COLUMN reminder_date DATE;
ALTER TABLE tasks ADD COLUMN contract_version BIGINT;

ALTER TABLE tasks ADD CONSTRAINT tasks_cancellation_review_complete CHECK (
    (automation_kind IS NULL AND contract_id IS NULL AND contract_name IS NULL
        AND cancellation_deadline IS NULL AND reminder_date IS NULL AND contract_version IS NULL)
    OR
    (automation_kind = 'CONTRACT_CANCELLATION_REVIEW' AND contract_id IS NOT NULL
        AND contract_name IS NOT NULL AND cancellation_deadline IS NOT NULL
        AND reminder_date IS NOT NULL AND contract_version IS NOT NULL)
);

CREATE UNIQUE INDEX tasks_contract_cancellation_occurrence_uidx
    ON tasks (contract_id, cancellation_deadline)
    WHERE automation_kind = 'CONTRACT_CANCELLATION_REVIEW';
