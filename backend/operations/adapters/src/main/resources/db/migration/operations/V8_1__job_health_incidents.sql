-- Operations context: incidents of recurring jobs that stopped running (issue #94, ADR-0014). Names, states and
-- times only; no job data and no household content.
CREATE TABLE job_health_incidents (
    id              UUID PRIMARY KEY,
    job_name        VARCHAR(100) NOT NULL,
    state           VARCHAR(20)  NOT NULL,
    last_success_at TIMESTAMPTZ,
    opened_at       TIMESTAMPTZ  NOT NULL,
    resolved_at     TIMESTAMPTZ
);

-- At most one open incident per job; resolved incidents stay as history.
CREATE UNIQUE INDEX job_health_incidents_open_uidx ON job_health_incidents (job_name) WHERE resolved_at IS NULL;
