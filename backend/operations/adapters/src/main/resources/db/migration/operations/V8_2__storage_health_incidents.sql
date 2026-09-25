-- Operations context: incidents of the external backup and of low disk space (issue #100, ADR-0015). Subjects, states,
-- failure codes, times and byte counts only; no command output and no household content.
CREATE TABLE storage_health_incidents (
    id              UUID PRIMARY KEY,
    subject         VARCHAR(40)  NOT NULL,
    condition       VARCHAR(20)  NOT NULL,
    failure_code    VARCHAR(80),
    last_success_at TIMESTAMPTZ,
    free_bytes      BIGINT,
    total_bytes     BIGINT,
    opened_at       TIMESTAMPTZ  NOT NULL,
    resolved_at     TIMESTAMPTZ
);

-- At most one open incident per subject; resolved incidents stay as history.
CREATE UNIQUE INDEX storage_health_incidents_open_uidx ON storage_health_incidents (subject) WHERE resolved_at IS NULL;
