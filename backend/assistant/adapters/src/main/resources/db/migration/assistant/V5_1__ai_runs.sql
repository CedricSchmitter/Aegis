-- Bounded AI runs (ADR-0003). Runs, their append-only audit steps and the ledger of applied effects.
-- Member ids are not foreign keys: the identity context owns members, and contexts do not share tables.

CREATE TABLE ai_runs (
    id                       uuid PRIMARY KEY,
    workload                 varchar(40)   NOT NULL,
    idempotency_key          varchar(200)  NOT NULL,
    retry_of                 uuid          REFERENCES ai_runs (id),
    requested_by             uuid          NOT NULL,
    requested_at             timestamptz   NOT NULL,
    provider                 varchar(40)   NOT NULL,
    model                    varchar(100)  NOT NULL,
    parameters               jsonb         NOT NULL DEFAULT '{}'::jsonb,
    sources                  jsonb         NOT NULL DEFAULT '[]'::jsonb,
    budget_max_duration_ms   bigint        NOT NULL CHECK (budget_max_duration_ms > 0),
    budget_max_cost          numeric(14, 6) NOT NULL CHECK (budget_max_cost > 0),
    currency                 char(3)       NOT NULL,
    budget_max_model_calls   integer       NOT NULL CHECK (budget_max_model_calls > 0),
    budget_max_tool_calls    integer       NOT NULL CHECK (budget_max_tool_calls >= 0),
    budget_max_output_tokens integer       NOT NULL CHECK (budget_max_output_tokens > 0),
    budget_max_attempts      integer       NOT NULL CHECK (budget_max_attempts > 0),
    state                    varchar(20)   NOT NULL
        CHECK (state IN ('QUEUED', 'RUNNING', 'WAITING_RETRY', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    attempt                  integer       NOT NULL DEFAULT 0,
    attempt_started_at       timestamptz,
    next_attempt_at          timestamptz,
    finished_at              timestamptz,
    cancel_requested         boolean       NOT NULL DEFAULT false,
    cancel_requested_by      uuid,
    model_calls              integer       NOT NULL DEFAULT 0,
    tool_calls               integer       NOT NULL DEFAULT 0,
    denied_tool_calls        integer       NOT NULL DEFAULT 0,
    attempt_model_calls      integer       NOT NULL DEFAULT 0,
    attempt_tool_calls       integer       NOT NULL DEFAULT 0,
    input_tokens             bigint        NOT NULL DEFAULT 0,
    output_tokens            bigint        NOT NULL DEFAULT 0,
    cache_read_tokens        bigint        NOT NULL DEFAULT 0,
    cache_write_tokens       bigint        NOT NULL DEFAULT 0,
    cost                     numeric(14, 6) NOT NULL DEFAULT 0,
    output                   text,
    failure_category         varchar(40),
    failure_code             varchar(100),
    failure_message          varchar(500),
    failure_at               timestamptz,
    step_count               integer       NOT NULL DEFAULT 0,
    version                  bigint        NOT NULL DEFAULT 0
);

-- At most one queued, running or waiting run per unit of work.
CREATE UNIQUE INDEX ai_runs_active_key ON ai_runs (idempotency_key)
    WHERE state IN ('QUEUED', 'RUNNING', 'WAITING_RETRY');
CREATE INDEX ai_runs_key_requested ON ai_runs (idempotency_key, requested_at DESC);
CREATE INDEX ai_runs_requested ON ai_runs (requested_at DESC);
CREATE INDEX ai_runs_running ON ai_runs (state) WHERE state = 'RUNNING';
CREATE INDEX ai_runs_finished ON ai_runs (finished_at DESC) WHERE finished_at IS NOT NULL;

CREATE TABLE ai_run_steps (
    run_id        uuid          NOT NULL REFERENCES ai_runs (id) ON DELETE CASCADE,
    seq           integer       NOT NULL,
    attempt       integer       NOT NULL,
    kind          varchar(30)   NOT NULL,
    name          varchar(100),
    outcome       varchar(100),
    input_tokens  bigint        NOT NULL DEFAULT 0,
    output_tokens bigint        NOT NULL DEFAULT 0,
    cache_read_tokens  bigint   NOT NULL DEFAULT 0,
    cache_write_tokens bigint   NOT NULL DEFAULT 0,
    cost          numeric(14, 6),
    currency      char(3),
    at            timestamptz   NOT NULL,
    PRIMARY KEY (run_id, seq)
);

-- Daily spending sums model-call costs by time.
CREATE INDEX ai_run_steps_model_calls ON ai_run_steps (at) WHERE kind = 'MODEL_CALL';

CREATE TABLE ai_run_effects (
    effect_key  varchar(200) PRIMARY KEY,
    run_id      uuid         NOT NULL REFERENCES ai_runs (id),
    applied_at  timestamptz  NOT NULL
);
