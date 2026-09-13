-- Planning context: tasks with separate due and planned dates and stable source links.
CREATE TABLE tasks (
    id                 UUID PRIMARY KEY,
    title              VARCHAR(200)  NOT NULL,
    note               VARCHAR(2000) NOT NULL DEFAULT '',
    owner_id           UUID,
    status             VARCHAR(10)   NOT NULL,
    due_date           DATE,
    planned_date       DATE,
    source_document_id UUID,
    created_by         UUID          NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,
    completed_at       TIMESTAMPTZ,
    completed_by       UUID,
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT tasks_status_check CHECK (status IN ('OPEN', 'DONE'))
);

CREATE INDEX tasks_status_due_idx ON tasks (status, due_date);
CREATE INDEX tasks_source_document_idx ON tasks (source_document_id);
