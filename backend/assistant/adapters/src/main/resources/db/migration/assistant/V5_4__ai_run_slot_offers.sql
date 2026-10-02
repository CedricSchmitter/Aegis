-- A freed AI slot is offered to the oldest run waiting for one (issue #135, ADR-0023). The offer reserves the slot
-- for that run until its next attempt starts, it ends, or the offer lapses after the slot wait.

ALTER TABLE ai_runs ADD COLUMN slot_offered_at timestamptz;

CREATE INDEX ai_runs_awaiting_slot ON ai_runs (requested_at, id) WHERE state IN ('QUEUED', 'WAITING_RETRY');
