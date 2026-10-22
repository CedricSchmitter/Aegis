-- Change history of documents (issue #47, ADR-0029): title, friendly filename, folder and own labels. Append-only: one
-- row per recorded change, written in the transaction of the change. seq orders the entries. Rows leave only with their
-- document when a member deletes it permanently. Member ids are not foreign keys; identity owns them.
CREATE TABLE document_changes (
    id              UUID        PRIMARY KEY,
    seq             BIGSERIAL   NOT NULL UNIQUE,
    document_id     UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    occurred_at     TIMESTAMPTZ NOT NULL,
    actor_kind      TEXT        NOT NULL CHECK (actor_kind IN ('MEMBER', 'AUTOMATION', 'AI_RUN')),
    actor_member    UUID,
    actor_reference TEXT,
    action          TEXT        NOT NULL,
    changes         JSONB       NOT NULL,
    undo_of         UUID        REFERENCES document_changes (id) ON DELETE CASCADE,
    CONSTRAINT document_changes_one_undo UNIQUE (undo_of),
    CONSTRAINT document_changes_member CHECK (actor_kind <> 'MEMBER' OR (actor_member IS NOT NULL
        AND actor_reference IS NULL)),
    CONSTRAINT document_changes_undo CHECK ((action = 'UNDONE') = (undo_of IS NOT NULL))
);
CREATE INDEX document_changes_document ON document_changes (document_id, seq DESC);

-- When recording began. Documents that existed before have earlier changes that were never recorded; nothing is
-- fabricated for them.
CREATE TABLE document_history_baseline (
    singleton       BOOLEAN     PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    recording_since TIMESTAMPTZ NOT NULL
);
INSERT INTO document_history_baseline (recording_since) VALUES (now());
