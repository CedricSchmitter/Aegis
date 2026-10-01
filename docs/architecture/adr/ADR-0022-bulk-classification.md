# ADR-0022: Existing documents are classified in an explicitly started, bounded bulk run

Status: accepted and implemented for issue #115. Date: 2026-10-01.

## Context

Since [ADR-0004](ADR-0004-automatic-document-classification.md) new uploads are classified once archived, and
[ADR-0005](ADR-0005-automatic-folder-placement.md) added folder placement to the same run. Documents that existed
before, or that were restored, are only classified when a member selects them: one at a time or up to ten at once.
A household with a few hundred older documents has to page through the list and select them in tens, without knowing
what the work will cost against the daily AI budget, and every finished document creates its own review card and a
notice for each member.

Issue #115 (carried over from #46, "optional, explicitly initiated backfill") asks for a bulk run with a preview and
the expected cost, bounded batches through the existing persisted jobs and AI runs, progress, cancellation, safe
resumption after a restart and no flood of cards. It must respect member decisions exactly like classification on
upload, wait for the next budget day like any other run ([ADR-0003](ADR-0003-bounded-ai-execution.md), issue #109)
and keep members without AI access from starting it (issue #107).

## Decision

### Scope and preview

A bulk run covers **documents that never received a classification result**: archived, outside the trash, and
without a completed analysis. The member narrows it to the whole collection, Unsorted, or one folder with or without
its subfolders. A document whose analysis is requested right now is left out, because its run already belongs to
someone. Documents that were classified before are not reprocessed in bulk; a member can still request that per
document.

`POST /api/documents/bulk-analyses/preview` changes nothing. It returns the number of documents (oldest upload first,
at most `aegis.documents.analysis.bulk.max-documents`, default 500, with a flag when the scope holds more), the first
ten titles, the refusal code a start would get for this member, a run already running, and the cost estimate:

- per document the mean cost of the last 20 succeeded classification runs on the model the member's runs would use
  (`AiRunQueries.estimate`, new), once there are at least three of them; otherwise the classification run budget,
  which no run may exceed;
- the estimate for all documents and the bound from the run budget;
- the provider's daily limit, today's spending, how many documents fit into what is left today, and the number of
  budget days the run is expected to need, or none when the daily limit cannot pay for one run.

These are estimates from list prices, labelled as such in the UI.

### Start

`POST /api/documents/bulk-analyses` with the scope and the preview's document count. The start is refused with

- the AI boundary's code (`ai.provider.memberNotAllowed`, `ai.notConfigured`, ...) when the member may not use AI:
  the runs are requested on the starting member's behalf, as for a member's own selection (ADR-0004);
- `analysis.bulk.active` while another bulk run is running; a partial unique index on the running state makes this
  hold for concurrent starts too;
- `analysis.bulk.changed` when the scope now holds a different number of documents, so the member confirms what they
  saw (approval bound to the data it was given for).

The start fixes the document list in the new `BulkAnalysis` aggregate of the documents context and schedules its
db-scheduler job (`document-bulk-analysis`, payload: run id and schema version) in the same transaction. Nothing else
schedules that job: there is no automatic bulk run, and no deployment or restart starts one. A restart continues a
run a member already started.

### Bounded batches through the existing path

Each job step is one transaction that locks the run, follows its runs in flight, requests the next documents and
reschedules itself (`poll-interval`, default 10 s). At most `batch-size` runs (default 3, at most 10) are in flight;
household concurrency of AI runs (default one) still applies on top.

A document is requested through `BulkAnalysisRequests`, implemented by the same service and with the same checks as a
member's selection: it must still exist, be archived, outside the trash, without a result and without an active run,
otherwise it is **skipped** with the reason. The request starts a new analysis generation with the new trigger
`BULK`, requested by the starting member, so the idempotency key, the source-version check, the effect ledger and the
apply policies of ADR-0004 and ADR-0005 are unchanged: confirmed, rejected and manual labels, a member's placement,
rejected titles, names, subjects and folder targets are respected exactly as on upload, and a member's edit during
the run makes its result fail as `STALE_SOURCE`.

Exactly-once per document:

- the item's move from pending to requested commits together with the AI run and its job, so a step that fails rolls
  both back;
- an item is requested once; a repeated or revived step finds it requested and only follows its run;
- a document that got a result since the start is skipped (`analysis.classified`);
- the AI run itself applies its effect once (effect ledger, ADR-0003).

A finished item records how its run ended: classified, failed (with the run's failure code), not classified (the
requester lost AI access, issue #107), or superseded when a member requested the document again meanwhile.

### Budget, refusals and cancellation

While any run in flight waits for the next budget day (`WAITING_RETRY` with `budget.daily`, issue #109), the bulk run
requests nothing more and shows the time it continues; its job looks again at that time, at the latest after
`max-wait` (default 15 minutes). A run therefore spreads over as many budget days as it needs without failing.

When the AI boundary refuses a request (not configured, provider not available to the member, member no longer
active), the run **stops** instead of marking every document: nothing more is requested, the document's analysis is
left untouched, runs in flight are still followed, and the run ends as `STOPPED` with the code.

Any member may cancel a running run. The cancellation marks pending documents cancelled, cancels the AI runs in
flight (in the same lock order as a run's completion: the run first, then the analysis) and takes their requests back
(`DocumentAnalysis.withdraw`): the document returns to its earlier state and a late answer of the cancelled run is
refused because its generation is no longer requested. Results already applied stay. Cancelled and stopped documents
are included again in the next run's preview.

### Results without a flood

The run's view counts progress from its items and reads review needs, folder proposals and failures from the
documents as they are now, so a member's decision since the run is reflected. It groups identical pending folder
proposals by their target key (ADR-0005) with one example document, whose proposal can be accepted for all documents
with the same target at once; failures and skips by reason with a few examples; and lists up to 50 documents to
review.

Documents whose current analysis has the trigger `BULK` get **no per-document review card** for a failed analysis or
open suggestions. Instead, the document review producer creates one card per finished run (`BULK_ANALYSIS`, reason
`BULK_CLASSIFICATION`, linking to the run's results) for the five most recently finished runs that still have
documents to review or still failing. The card publishes its notices once, like every card (ADR-0009). Opening the
query to the review reconciler is an explicit `requireReader` on `BulkAnalysisQueries.attention`; every command needs
a member.

### Persistence and portability

Migration `V2_9` adds `document_bulk_analyses` (scope, requester, state, stop code, cancellation, version) and
`document_bulk_analysis_items` (document, position, state, generation, outcome, code), and allows the trigger `BULK`.
Items cascade with a document that is deleted permanently (ADR-0021). Bulk runs are operational records like AI runs:
in the PostgreSQL recovery backup, not in the portable export. The trigger of an exported analysis is kept as is.

## Consequences

- The household can work off its whole existing collection in a few explicit runs, within the daily budget, and sees
  the cost before it starts.
- A run that waits for budget days holds the household's single AI slot only while one of its runs is actually
  running; uploads keep being classified in between.
- Results of a bulk run are reviewed on its result page and the documents themselves. A document from a run older than
  the five most recent finished runs keeps its badge in the list but no longer has a card in the inbox.
- Documents classified before are not part of bulk runs; reprocessing the collection in bulk would need its own
  decision about overwriting earlier results and costs.
- Tests: `BulkAnalysisTest` (batching, stop, cancellation), `BulkAnalysisServiceTest` (preview and cost, start
  rules, bounded steps that repeat safely, budget wait, skips, stale result, cancellation, refusals, authorization,
  grouping) and `BulkAnalysisIT` against PostgreSQL with the real scheduler (bounded batch while a call is held,
  repeated steps, cancellation of a run in flight whose late answer is not applied, a stale result, one grouped
  card, refusals).
