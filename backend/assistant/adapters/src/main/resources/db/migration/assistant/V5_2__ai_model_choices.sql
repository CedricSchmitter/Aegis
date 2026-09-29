-- Household model family per workload (ADR-0020). Workloads without a row use their configured default family.
-- changed_by is not a foreign key: the identity context owns members, and contexts do not share tables.

CREATE TABLE ai_model_choices (
    workload   varchar(40) PRIMARY KEY
        CHECK (workload IN ('DOCUMENT_CLASSIFICATION', 'DOCUMENT_REVIEW', 'CHAT')),
    family     varchar(20) NOT NULL CHECK (family IN ('OPUS', 'SONNET', 'HAIKU')),
    version    bigint      NOT NULL CHECK (version > 0),
    changed_by uuid        NOT NULL,
    changed_at timestamptz NOT NULL
);
