# ADR-0028: Near duplicates are hinted from a text fingerprint and never removed

Status: accepted and implemented. Date: 2026-10-22.

## Context

Issue #42 asks to use checksums for identical content and non-destructive hints for near duplicates, without
deleting similar but distinct records. Identical uploads are already caught by the SHA-256 checksum of the original.
The same letter scanned twice, photographed once and scanned once, or exported again as PDF has different bytes and
is stored twice without notice. Monthly invoices of one provider share almost all of their wording and are distinct
records that must not be reported as duplicates.

## Decision

1. **A fingerprint of the archive text, kept with the text check.** `TextFingerprint` (documents domain) summarizes
   the OCR text the archive returns: a MinHash signature of 64 slots over the overlapping three-word sequences and the
   hashes of up to 256 distinct number tokens (amounts, dates, contract and invoice numbers). Words are folded
   (lower case, accents removed, `ß` as `ss`) and one-character tokens are dropped as OCR noise. It holds hashes only,
   so the text cannot be recovered from it. It is stored as `text_fingerprint` (`bytea`, migration `V2_11`) with the
   measurement of [ADR-0012](ADR-0012-recognized-text.md), from the same read of the archive text, and like it is
   derived, not exported and replaced when the document's archive document changes. The first byte is the algorithm
   version; a fingerprint of another version is ignored and made again.
2. **Near duplicate means similar wording and the same numbers.** Two fingerprints are near duplicates when both
   texts have at least 20 word sequences, at least half of the MinHash slots agree and at least 80 % of the distinct
   numbers of both texts agree (compared exactly up to 256 numbers, otherwise estimated from the smallest hashes).
   The numbers keep template records apart: on synthetic German examples a rescan with a misread letter in about one
   of 25 words and one misread digit agreed in at least 0.58 of the words and 0.89 of the numbers, two monthly
   invoices in up to 0.86 of the words but at most 0.71 of the numbers, and unrelated letters in none of the words.
   These thresholds are provisional until they are checked against real household scans (#43).
3. **Backfill without holding up new documents.** Checks measured before fingerprints existed keep their count and
   archive version and stay `RECOGNIZED`/`LITTLE`/`NONE`; the sweep reads their text again after all other due
   documents, oldest check first. A failed backfill keeps the measurement, does not count as a failed attempt and
   moves the check to the end of the queue, so an unreadable document cannot block the others.
4. **Hints, never actions.** `SimilarDocumentQueries` compares the fingerprints of archived, non-trashed documents
   whose check belongs to their current archive document in memory, which suits a household archive of a few
   thousand documents. `GET /api/documents/{id}/similar` lists up to ten near duplicates, most similar first, and the
   document page shows them with links and upload dates. The review producer adds one card per pair (category
   `DUPLICATE`, reason `SIMILAR_DOCUMENT`, fingerprint `similar:<first>:<second>`, source the earlier upload). The
   card has no actions: the members open both documents and decide themselves, for example by moving one to the
   trash. Trashing or purging either document supersedes the card; a dismissal holds while the pair stays the same.
   Nothing is merged, linked, hidden or deleted automatically.

## Consequences

- A document archived again (new archive document) gets a new fingerprint with its new measurement.
- Records whose distinguishing content is only in words (for example two letters sharing a template and no
  numbers) and records with very little text are not reported; a hint is only as good as the archive's OCR.
- The comparison is quadratic in the number of archived documents; an index (locality-sensitive hashing buckets) is
  needed only if the archive grows by orders of magnitude.
