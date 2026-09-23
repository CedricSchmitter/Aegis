# ADR-0009: The central review inbox stores workflow references, not business facts

Status: accepted and implemented for issue #50. Date: 2026-09-21.

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

## Consequences

- Both members see the same cards and race through optimistic concurrency rather than last-write-wins.
- A delegated retry resolves its card only after the source command succeeds. A later failure creates a new
  versioned card, while processing remains visible on the document.
- A disappeared source cannot receive a stale command: reconciliation supersedes its card and actions revalidate the
  source and version.
- Product text lives in the German and English catalogs; stored keys, reasons and statuses are locale-independent.
