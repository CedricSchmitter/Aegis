# ADR-0009: The central review inbox stores workflow references, not business facts

Status: accepted and implemented for issue #50. Date: 2026-09-21. Background reconciliation added for issue #89 on
2026-09-23.

## Context

Archive failures, uncertain document classification and proposed or conflicting contract facts already have
authoritative persisted state and context-specific commands. A household member needs one actionable list across
those contexts, but copying their values or processing state into a new aggregate would create a second source of
truth and let generic inbox actions bypass document and contract invariants.

## Decision

The `review` bounded context owns only a durable workflow card: stable id, producer fingerprint, source identity and
version, reason/category/priority, localized presentation key and parameters, deep link, available command names,
lifecycle, decision audit and optimistic version. `OPEN`, `RESOLVED`, `DISMISSED` and `SUPERSEDED` are review
lifecycle states; they are independent of document processing, contract fact review, task completion and notice read
state.

Producer adapters read public application contracts. The live producers are failed document archival, documents in
which the archive recognized no or almost no text ([ADR-0012](ADR-0012-recognized-text.md)), failed or unavailable
document analysis, classification suggestions awaiting decisions, proposed contract facts and contract fact
conflicts. Fingerprints contain source, version and reason. Reconciliation preserves a dismissal while that
fingerprint is unchanged, creates a linked new card for materially new evidence and supersedes an open card that is
no longer current. Mail exceptions, duplicate/relationship suggestions and task candidates are not simulated.

Commands are explicit and version-bound. Retry adapters call document application services after re-reading the
source version. Classification and contract decisions stay on the source screens, reached through deep links, where
their existing confirm, correct and reject commands show evidence and enforce domain rules. There is no heterogeneous
bulk approval command.

New cards publish one durable `REVIEW_ITEM` notice per active member with a review deep link. Reading that notice does
not decide the card. Portable export schema 7 carries every review reference and lifecycle record; restore is
insert-only by stable id/fingerprint, so it does not overwrite newer local decisions. PostgreSQL backup captures the
same table.

### Background reconciliation (issue #89)

**Trigger.** Reconciliation runs when a member opens the inbox (`GET /api/reviews`) and in the recurring db-scheduler
job `reconcile-review-inbox`, every `aegis.review.reconciliation-interval`. The owner chose once a day (default
`1d`): opening the inbox shows every current card at once, and the daily run is the safety net that makes a new card
and its notices reach both members within a day even if nobody opens the inbox. The schedule is stored in PostgreSQL, one execution runs at a time and a restart continues it; a failed run is
repeated at the next interval, not retried early. Each run is one transaction, so a card and its notices commit
together and a run that stops before committing leaves neither behind. The cards are the job's only progress: a
repeated run creates nothing twice.

**Identity.** The job runs as the review reconciler, `Actor.householdProcess("review-reconciler")`
(`ReviewReconciliation.RECONCILER`): a household process that neither is nor impersonates a member.
`HouseholdAccess.requireReader` opens to household processes exactly the queries reconciliation needs: document
search, the contract list and contract details, and the member directory. `requireMember`, which every command and
every other query requires, rejects it, and it has no member id that could be recorded as an author. It can therefore
read the producers, create and supersede cards and publish their notices, but it can neither act on nor dismiss a card
nor change a document, contract or task; the review service accepts no other household process for reconciliation.
A member opening the inbox reconciles as that member. Superseding records no member in either case, because no
member decided it; resolving and dismissing always record the member.

**Concurrency.** Each reconciliation first takes a transaction-scoped PostgreSQL advisory lock, so the job and
members opening the inbox reconcile one after another, and a later reconciliation reads the cards an earlier one
committed; a member opening the inbox during a run waits for it. The card id derives from the fingerprint, cards are
inserted with `ON CONFLICT DO NOTHING` (so a restore inserting the same card cannot make reconciliation fail either)
and only the insert that created a card publishes its notices, which are also unique per member and card. When a
member dismisses or resolves a card while reconciliation supersedes it, the member's decision stands.

**Cost.** A run reads every document outside the trash through document search in pages of 100 (a fixed number of
queries per page, and no archive or AI requests), every contract summary plus the details of contracts with pending
facts or conflicts, the existing fingerprints of all current candidates in one query and the open cards in one query.
It writes only new and superseded cards and new notices. Measured on the household instance on 2026-09-23 (5
documents, no contracts, 8 cards): a job run that created two cards and their notices took 114 ms, one that superseded
two cards 35 ms, and opening the inbox, which reconciles and lists, 47 to 69 ms. The work grows with one page of
queries per 100 documents, so even a short interval would stay far above the cost of a run for a household collection.

## Consequences

- Both members see the same cards and race through optimistic concurrency rather than last-write-wins.
- A delegated retry resolves its card only after the source command succeeds. A later failure creates a new
  versioned card, while processing remains visible on the document.
- A disappeared source cannot receive a stale command: reconciliation supersedes its card and actions revalidate the
  source and version.
- Product text lives in the German and English catalogs; stored keys, reasons and statuses are locale-independent.
- A new card notifies the members who are active when it is created, within the reconciliation interval, even if
  nobody opens the inbox. The interval bounds that delay, not correctness: opening the inbox still reconciles at once.
- Household processes are a separate kind of actor. Opening another query to them is an explicit change of that
  query's authorization (`requireReader`), reviewed like any other.
