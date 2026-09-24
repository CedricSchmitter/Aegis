# ADR-0014: Members get one notice per incident when a recurring job stops running

Status: accepted and implemented for issue #94. Date: 2026-09-24.

## Context

Since [ADR-0013](ADR-0013-recurring-job-health.md) the status page shows whether the recurring jobs
`reconcile-review-inbox`, `check-archive-text` and `reconcile-documents` still run, but nobody is told when one stops.
If the daily review reconciliation keeps failing, members miss new review notices until someone happens to open the
status page. The in-app notice inbox already exists and deduplicates durably per recipient and key.

## Decision

1. **A watch of its own.** The recurring job `watch-job-health` runs every `aegis.operations.job-health-watch-interval`
   (default five minutes) and evaluates the household jobs with the same `JobHealthPolicy` as the status page. It is
   not part of any watched job, so a watched job that fails or lost its schedule cannot stop it. The watch does not
   watch itself; the status page lists it like every other recurring job.
2. **Incidents.** A job whose state is `STALE` or `NOT_SCHEDULED` opens an incident in `job_health_incidents` (operations
   context) with its state and last success at that time. A partial unique index keeps at most one open incident per
   job. The incident closes when the job's state is `OK` again, and a later outage opens a new incident. `FAILING` and
   `PENDING` neither open nor close an incident: a single failed run is still shown only on the status page, and a
   job that lost its schedule stays in its incident until it has succeeded again. `UNKNOWN` (unreadable scheduler
   state) fails the watch's own run instead.
3. **Notices.** While an incident is open, each check publishes a `JOB_STALE` notice to every active member with the
   deduplication key `job-health:<incident id>`, so every member gets exactly one notice per incident, also a member
   activated during the outage. The notice carries only the job name, the state and the last success (`job`, `state`,
   `lastSuccessAt`) and links to the status page (`STATUS` link with the job name); never job payloads or household
   content. The web app shows the localized job name from the status page catalog in German or English.
4. **No multi-step transaction.** Opening an incident and publishing the notices are separate idempotent steps: a
   check that stopped half way completes its notices on the next run. Each job is checked on its own, so a job whose
   check fails does not hide the others; the run then fails after all jobs were checked and the status page shows the
   watch as failing.
5. **Start grace.** Right after an application start, jobs that were due during a downtime have not run yet and look
   stale. The watch opens no incident until 10 minutes (the grace of ADR-0013) after it started, so a restart after a
   power cut or a longer maintenance window does not notify anybody when the jobs then run normally.
6. **Dependencies.** The operations application uses the public `MemberDirectory` (identity) and `PublishNotice`
   (notifications) contracts, as the review context does ([ADR-0009](ADR-0009-central-review-inbox.md)). The watch
   reads the member list as the household process `job-health-watch`.

## Consequences

- A daily review job that fails twice in a row notifies both members within one watch interval of becoming stale
  (at most about two days and 15 minutes after its last success); a one-minute job within about 17 minutes.
- A job that stays broken produces one notice per member, not one per check; after it recovered and fails again, a
  new notice follows.
- Incidents are operational state, not household data: they are not part of the portable export, and a restore
  starts without them. A job that is still stale after a restore opens a new incident.
- When the whole scheduler table is emptied, the watch has no schedule either until the next application start; the
  status page still shows every job as not scheduled.
- Notices remain in-app only. Web Push (#28) could deliver the same notice to the phones later.
- Tests with a controlled clock cover a job becoming stale, repeated checks, a check that stopped half way, recovery
  and a new incident, a job without schedule, the start grace and a failing job check. A PostgreSQL integration test
  covers concurrent opening and notices for both members through the inbox.
