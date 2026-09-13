-- db-scheduler persistent job table (PostgreSQL schema from db-scheduler 16.x).
CREATE TABLE scheduled_tasks (
    task_name            TEXT        NOT NULL,
    task_instance        TEXT        NOT NULL,
    task_data            BYTEA,
    execution_time       TIMESTAMPTZ NOT NULL,
    picked               BOOLEAN     NOT NULL,
    picked_by            TEXT,
    last_success         TIMESTAMPTZ,
    last_failure         TIMESTAMPTZ,
    consecutive_failures INTEGER,
    last_heartbeat       TIMESTAMPTZ,
    version              BIGINT      NOT NULL,
    priority             SMALLINT,
    PRIMARY KEY (task_name, task_instance)
);

CREATE INDEX scheduled_tasks_execution_time_idx ON scheduled_tasks (execution_time);
CREATE INDEX scheduled_tasks_last_heartbeat_idx ON scheduled_tasks (last_heartbeat);
