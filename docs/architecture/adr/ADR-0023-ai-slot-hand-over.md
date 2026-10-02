# ADR-0023: A freed AI slot is handed to the oldest waiting run

Status: accepted and implemented for issue #135. Date: 2026-10-02.

## Context

[ADR-0003](ADR-0003-bounded-ai-execution.md) limits how many AI runs work at the same time (`aegis.ai.max-concurrent-runs`,
default one, plus a limit per workload). Each run has its own db-scheduler job. A job that starts while the slot is
taken used to be deferred by a fixed slot wait (`aegis.ai.slot-wait`, 15 s) and check again.

Runs deferred together come due together. db-scheduler picks them in the same poll, and the exclusive AI lock lets
only the first one take the slot; the others are deferred together again. With one slot, the k-th queued run waited
k × 15 s, even when every run finished after a few seconds. Bulk classification
([ADR-0022](ADR-0022-bulk-classification.md)) always keeps several runs in flight, so its documents started about
15 s apart. The traces in PR #134 show the pattern.

Waking a waiting run when the slot frees has a race: the waiting run's job may be executing at that moment. Its
`begin` may already have decided to wait, and its completion handler is about to store `now + slot wait`.
db-scheduler refuses to move an execution that is picked, and the completion handler would overwrite a move made
before it.

## Decision

**Hand-over after every execution that may free a slot.** After an execution of a run ended an attempt or found the
run ended (`RunExecutor`), a separate short transaction takes the exclusive AI lock (`RunExecution.offerFreeSlots`).
It loads the runs that are due and only miss a slot (queued, or waiting for a retry that is due, not cancelled),
oldest request first. Each one that fits under the global and its workload's limit gets the slot:

- The offer is recorded on the run (`ai_runs.slot_offered_at`). While it is valid, `begin` of every other run counts
  it as an occupied slot, so a younger run whose job happens to come due first cannot take it (oldest first).
- The run's job is moved to now through the `RunScheduler` port (`wake`). The adapter moves only an execution that is
  not picked; after the commit it also triggers a scheduler poll, because the poll db-scheduler triggers itself may run
  before the commit.
- A run without a job (restored data) gets no offer; otherwise it would hold the slot without ever taking it up.

**The race is closed under the same lock.** After the job's completion handler stored the next time of a run that
was deferred, it calls `RunExecution.wakeIfOffered`, which takes the AI lock and moves the job to now if the run holds
an offer. The orderings:

- The offer is made before the waiting run's `begin`: `begin` sees the offer and starts the run.
- The offer is made after `begin` decided to wait, and the job is no longer picked: `wake` moves it.
- The offer is made while the job is still picked: `wake` cannot move it, but the offer is committed before the lock
  is released. `wakeIfOffered` runs after the completion handler stored the time and needs the same lock, so it sees
  the offer and moves the job.

**The slot wait stays as the fallback.** An offer ends when the run's next attempt starts or the run ends, and it
lapses after one slot wait. A run whose hand-over did not happen (process stopped between the attempt and the
hand-over, failed hand-over transaction, offer lapsed, occupying run presumed dead) checks again after the slot wait,
as before. A failed hand-over is logged and does not change the outcome of the execution that triggered it.

Application services stay framework-independent: the offer is domain state of `AiRun` (`awaitsSlot`, `offerSlot`,
`holdsSlotOffer`), the hand-over is application logic, and db-scheduler details (picked executions, version checks,
the extra poll) stay in `DbSchedulerRunScheduler` and `AiRunTask`.

## Consequences

- Queued runs start right after the previous run ends, within the scheduler's polling interval (2 s in production),
  in request order. Bulk classification works through its batch without the 15 s gaps.
- The integration tests use production's slot wait again; `AiSlotHandOverIT` shows queued runs starting right after
  each other with it.
- Each execution that frees a slot costs one more short transaction under the AI lock. The number of waiting runs is
  bounded by the bulk batch size and the members' own requests.
- An offer reserves a slot for at most one slot wait. If the woken job is not executed within that time (scheduler
  stalled), the slot stays unused for that long, which is no worse than the fixed wait before.
- Offers are not shown to members and add no audit step; the run stays `QUEUED` or `WAITING_RETRY` until its attempt
  starts.
