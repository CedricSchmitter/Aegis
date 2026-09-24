# ADR-0013: The status page reports recurring job health from the scheduler's persisted state

Status: accepted and implemented for issue #92. Date: 2026-09-23.

## Context

Three recurring db-scheduler jobs keep the household data current: `reconcile-review-inbox` creates review cards and
their notices once a day ([ADR-0009](ADR-0009-central-review-inbox.md)), `check-archive-text` measures recognized text
once a minute ([ADR-0012](ADR-0012-recognized-text.md)) and `reconcile-documents` resumes interrupted archival and
cleans up staging files every five minutes. A job that kept failing was visible only in the application log, so for
example missing review notices would go unnoticed. AGENT.md asks to surface stale integrations and the last successful
synchronization. db-scheduler already persists, per task, `last_success`, `last_failure`, `consecutive_failures` and
the next `execution_time` in `scheduled_tasks`.

## Decision

1. **Operations reads the scheduler's state through a port.** `RecurringJobStateSource` (operations application)
   returns per job name the last success, last failure, consecutive failures and next run. Its adapter reads
   `scheduled_tasks` rows of the recurring instance only and never selects `task_data`, so no job payload or household
   content reaches the status page. The status stays a member-only query (`SystemStatusQuery`).
2. **The composition root declares the jobs.** Each job class exposes its name and interval (`NAME`, `INTERVAL`, or
   the configured `aegis.review.reconciliation-interval`), and the bootstrap wiring passes them to the status service
   as `RecurringJob` values. db-scheduler does not expose a recurring task's schedule, and a declaration in one place
   avoids another dependency between contexts. An integration test checks that every registered recurring task is
   reported.
3. **One staleness rule, derived from the interval.** A job runs one interval after its previous run ended (fixed
   delay), and a failed run is repeated one interval later, not earlier. Therefore:
   - `OK`: the latest run succeeded and the last success is recent.
   - `FAILING`: the latest run failed (`consecutive_failures > 0`), but the last success is not yet stale. The page
     shows the time of the last failure and how many runs failed in a row.
   - `STALE`: the last success is older than **two intervals plus 10 minutes**, that is the run after a failed or
     missed one did not succeed either. A job that never succeeded is stale after two failures in a row, or when its
     first run is overdue by more than an interval plus 10 minutes. The grace covers the run itself and a restart
     during a deployment.
   - `PENDING`: scheduled, but its first run is not due yet. `NOT_SCHEDULED`: no row (for example when the table was
     emptied, until the next application start). `UNKNOWN`: the state could not be read; the rest of the page still
     shows.

   | Job | Interval | Stale without a success for |
   |---|---|---|
   | `reconcile-review-inbox` | 1 day (`aegis.review.reconciliation-interval`) | 2 days 10 minutes |
   | `check-archive-text` | 1 minute | 12 minutes |
   | `reconcile-documents` | 5 minutes | 20 minutes |
4. **Presentation.** The status page lists each job with a localized name and purpose, how often it runs, its last
   success, next run and, while it is failing, its last failure. A warning at the top appears when a job is failing,
   stale, not scheduled or unknown. The API returns stable state codes and the interval in seconds; German and English
   texts live in the catalogs.

## Consequences

- A daily review job that fails once shows "Letzter Lauf fehlgeschlagen" the same day; only a second missed day makes
  it stale. A job that runs every minute is marked stale after 12 minutes, which a normal deployment restart does not
  reach.
- A job that runs successfully but does no useful work (for example an archive that cannot be reached during the text
  sweep) stays `OK`; that is the corresponding integration's status, not the job's.
- The status page itself notifies nobody. Since issue #94 the recurring job `watch-job-health` sends the members a
  notice when a job becomes stale or is no longer scheduled ([ADR-0014](ADR-0014-stale-job-notices.md)); broader
  maintenance checks remain with #39.
- A new recurring job must be added to the declaration in the composition root; the integration test fails until it
  is.
