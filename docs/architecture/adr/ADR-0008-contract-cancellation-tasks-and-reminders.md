# ADR-0008: Cancellation automation is a durable planning projection of confirmed contract facts

Status: accepted and implemented for issue #63. Date: 2026-09-20.

## Context

Contracts owns observations, review state and deterministic cancellation-deadline calculation (ADR-0007).
Planning owns tasks, actual due dates and planned work dates. Notifications owns inbox delivery and read state.
Issue #63 connects those contexts without making AI, a calendar or phone push part of the correctness path.

The connection must survive restarts and missed dates, stay idempotent under at-least-once execution, preserve a
member's task edits and completion decisions, and respond safely when a confirmed deadline is corrected or the
contract is resolved.

## Decision

1. Every contract command that can affect a deadline publishes a db-scheduler reconciliation job in the same
   database transaction as the contract change. The contracts context sees only the public
   `ContractReconciliationScheduler` contract. Job payloads contain schema version 1, contract id and requesting
   member id; they do not serialize aggregates. Each mutation gets a one-shot job, so an already queued daily job
   cannot delay a same-day correction.
2. The job calls `ContractDeadlineQuery`, the contracts public application contract, with the requesting member's
   authorization and the current Europe/Berlin local date. It never reads contract tables. One separate stable job
   per contract reschedules itself for 00:05 on the next Berlin day, providing continuing reconciliation and
   downtime catch-up while mutation jobs stop after one successful pass.
3. Planning projects each materially distinct cancellation deadline to one stable task id derived from contract id
   and deadline. A database uniqueness constraint independently protects that occurrence identity. The task's
   actual due date is the contractual cancellation deadline; owner and planned work date are separate and begin
   unassigned/unplanned.
4. Planning stores a small snapshot with the task: contract id/name/end, deadline, reminder date and source
   contract version. The REST API exposes it as `cancellationReview`; the web UI localizes the title and details.
   The source document stays a normal task source link. Export schema 6 preserves this projection.
5. Reconciliation updates only contract-owned snapshot fields and the actual due date. The generated title follows a
   contract rename only while untouched; a member-customized title, note, owner, planned date and completion are
   preserved. Members cannot manually move the contractual due date; they can freely move the planned date. A
   corrected deadline creates a new occurrence and marks only unresolved stale automatic work `CANCELLED`.
   Completed work remains completed and is never recreated by unchanged processing.
6. One second durable job is scheduled for the reminder date at the start of the Europe/Berlin day. A late entry
   schedules it immediately. Before publication it rechecks that the occurrence is still open and current. Stale
   or completed occurrences therefore produce no notice even if an old job row executes.
7. Every active household member is an explicit recipient. Notifications receive one stable deduplication key per
   task occurrence and recipient. The database enforces durable uniqueness independently of read state, so reading
   a notice never permits an identical retry to publish another. Reading a notice does not touch the task, and
   completing the review task does not change contract status.

## Consequences

- The workflow is deterministic and makes no model call. Missing, extracted-only, rejected or conflicting facts
  still produce no deadline and therefore no task.
- Today reuses its existing seven-day policy. The task appears in the general task list immediately and in Today
  from deadline minus seven days through due and overdue states until completion.
- db-scheduler owns time-triggering and downtime catch-up. PostgreSQL owns task occurrence and notification
  deduplication. Contract, planning and notification repositories remain private to their contexts.
- `CANCELLED` on a task means an automatic occurrence was superseded; it does not state that the contract itself
  was cancelled. Contract status remains a separate member decision.
- In-app notices are durable only inside Aegis. They do not imply delivery while the phone app is closed; Web Push
  and other external delivery channels remain separate integrations.
