-- Attempts that ended only because the household's daily AI cost limit was reached (issue #109). The run waits for
-- the next budget day, and these attempts are not counted against budget_max_attempts.

ALTER TABLE ai_runs ADD COLUMN budget_waits integer NOT NULL DEFAULT 0
    CHECK (budget_waits >= 0 AND budget_waits <= attempt);
