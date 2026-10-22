-- Change history of tasks (issue #47, ADR-0030). Append-only: one row per saved change, written in the transaction of
-- the change. task_version is the task's version after the change, so a saved state is recorded at most once. Rows
-- leave only with their task when a member deletes it permanently. Member ids are not foreign keys; identity owns them.
CREATE TABLE task_changes (
    id              UUID        PRIMARY KEY,
    task_id         UUID        NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    task_version    BIGINT      NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL,
    actor_kind      TEXT        NOT NULL CHECK (actor_kind IN ('MEMBER', 'AUTOMATION', 'AI_RUN')),
    actor_member    UUID,
    actor_reference TEXT,
    action          TEXT        NOT NULL,
    changes         JSONB       NOT NULL,
    undo_of         UUID        REFERENCES task_changes (id) ON DELETE CASCADE,
    CONSTRAINT task_changes_version UNIQUE (task_id, task_version),
    CONSTRAINT task_changes_one_undo UNIQUE (undo_of),
    CONSTRAINT task_changes_member CHECK (actor_kind <> 'MEMBER' OR (actor_member IS NOT NULL
        AND actor_reference IS NULL)),
    CONSTRAINT task_changes_undo CHECK ((action = 'UNDONE') = (undo_of IS NOT NULL))
);

-- When recording began. Tasks that existed before have earlier changes that were never recorded; nothing is
-- fabricated for them.
CREATE TABLE task_history_baseline (
    singleton       BOOLEAN     PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    recording_since TIMESTAMPTZ NOT NULL
);
INSERT INTO task_history_baseline (recording_since) VALUES (now());
