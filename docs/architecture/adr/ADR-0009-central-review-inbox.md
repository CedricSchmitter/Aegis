# ADR-0009: The central review inbox stores workflow references, not business facts

Status: accepted and implemented for issue #50. Date: 2026-09-21. Background reconciliation added for issue #89 on
2026-09-23. Combined with the notice list into one inbox for issue #83 on 2026-09-25.

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

New cards publish one durable `REVIEW_ITEM` notice per active member with a review deep link. The notice carries the
card's presentation key and parameters (issue #91), so it names the reason and the source, for example the document
title in its original language. Reading that notice does not decide the card. Since issue #83 the inbox shows the
card instead of listing this notice (see [Combined inbox](#combined-inbox-issue-83)). Portable export schema 7 carries every review reference and lifecycle record; restore is
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

### Combined inbox (issue #83)

**Context.** The walkthrough of issue #83 (finding F3) found two inboxes: every open card also appeared under
"Hinweise" as a `REVIEW_ITEM` notice with the same text, a failed archival appeared as a notice and as a card, and
most unread notices said that an upload had been archived, although the upload already confirmed saving. The owner
decided on 2026-09-25 to show one inbox.

**Decision.**

1. **One page.** The web app has one navigation entry, "Eingang" / "Inbox" (`/inbox`), in place of "Prüfen" and
   "Hinweise". Its section "Zu prüfen" / "To review" shows the cards with unchanged behavior (state filter, retry,
   source links, optimistic concurrency; the category filter left with the redesign, ADR-0019 decision 17). Its section "Mitteilungen" / "Messages" lists the member's
   notices that no card represents. `/review` (with `?item=<card id>`) and `/notices` redirect to `/inbox`, so links in
   notices, bookmarks and future push messages keep working.
2. **Each notice kind has one place.** `NoticeKind` in the notifications domain states it: `MESSAGE` (task assigned
   or completed, cancellation reminder, stale job, backup failed, disk space low) is listed; `REVIEW_CARD`
   (`REVIEW_ITEM`, `DOCUMENT_ARCHIVAL_FAILED`) is still published, because it is the delivery trigger for other
   channels (#28, #30), but `GET /api/notices` does not list it and the unread count leaves it out; `RETIRED`
   (`DOCUMENT_ARCHIVED`) is no longer published and a publish request for it is rejected.
3. **Reading card notices.** After the inbox has loaded (and so reconciled) the cards, it calls
   `POST /api/notices/review-cards/read`, which marks the member's unread `REVIEW_CARD` notices read. This changes only
   that member's notice read state: the cards stay open until a member dismisses or resolves them, and nothing is
   claimed about delivery. Marking all messages read still marks every unread notice.
4. **No success notice.** The documents context no longer emits a "document archived" event; a successful archival
   is visible on the document. The `DOCUMENT_ARCHIVED` enum value stays so stored rows keep loading. Migration
   `V4_3` marks stored unread ones read and deletes nothing; notices are not part of the portable export, so export
   and restore are unaffected.
5. **One badge.** The navigation badge is the number of open cards (`GET /api/reviews/open-count`, a stored count
   without reconciliation, so polling it every 30 seconds stays cheap) plus the member's unread messages
   (`GET /api/notices/unread-count`). The web app adds the two; neither context reads the other's tables.

**Consequences.** A card appears once. Card notices remain unread until the member opens the inbox, so a future push
channel can still deliver them. A failed archival reaches the badge once its card exists, that is when a member opens
the inbox or the daily reconciliation runs; until then the document itself shows the failure. The badge counts open
cards for every member until someone decides them, as the cards are shared. `NoticeServiceTest` and
`ReviewServiceTest` cover the placement, listing, counting and read rules; `CombinedInboxIT` covers the migration,
the read marking per member, the badge counts, a failed archival and the absence of success notices against
PostgreSQL.

## Consequences

- Both members see the same cards and race through optimistic concurrency rather than last-write-wins.
- A delegated retry resolves its card only after the source command succeeds. A later failure creates a new
  versioned card, while processing remains visible on the document.
- A disappeared source cannot receive a stale command: reconciliation supersedes its card and actions revalidate the
  source and version.
- Product text lives in the German and English catalogs; stored keys, reasons and statuses are locale-independent.
  Cards for failed archival or analysis store the stable failure code; the frontend presents it as a short localized
  reason, falling back to a generic one for unknown codes (issue #96). The code itself stays visible on the document.
- A new card notifies the members who are active when it is created, within the reconciliation interval, even if
  nobody opens the inbox. The interval bounds that delay, not correctness: opening the inbox still reconciles at once.
- Household processes are a separate kind of actor. Opening another query to them is an explicit change of that
  query's authorization (`requireReader`), reviewed like any other.
