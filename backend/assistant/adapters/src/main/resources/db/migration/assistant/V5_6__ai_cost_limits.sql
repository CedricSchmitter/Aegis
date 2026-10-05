-- Household cost limits per workload. Workloads without a row use the configured defaults: the provider's daily
-- limit shared with the other workloads without limits of their own and the profile's or provider's run budget.
-- Amounts are in the configured AI currency, in whole cents. changed_by is not a foreign key: the identity context
-- owns members, and contexts do not share tables.

CREATE TABLE ai_cost_limits (
    workload     varchar(40)    PRIMARY KEY
        CHECK (workload IN ('DOCUMENT_CLASSIFICATION', 'DOCUMENT_REVIEW', 'CHAT')),
    daily_limit  numeric(14, 6) NOT NULL CHECK (daily_limit > 0),
    run_limit    numeric(14, 6) NOT NULL CHECK (run_limit > 0 AND run_limit <= daily_limit),
    currency     char(3)        NOT NULL,
    version      bigint         NOT NULL CHECK (version > 0),
    changed_by   uuid           NOT NULL,
    changed_at   timestamptz    NOT NULL
);
