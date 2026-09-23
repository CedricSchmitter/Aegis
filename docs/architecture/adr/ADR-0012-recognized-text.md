# ADR-0012: Whether the archive recognized text is derived data of the documents context

Status: accepted and implemented for issue #87. Date: 2026-09-23.

## Context

The live check of issue #80 (ADR-0011) showed that Paperless-ngx 3.1.3 archives a photographed letter rotated by 7
degrees or more, or a very dark, noisy photo, without recognizing any text. Aegis showed such a document as
"Archiviert" like a searchable one, while full-text search never found it. With AI available, the classification
flags `NO_TEXT` (ADR-0004) and the review inbox shows the document; without AI, or for a member the subscription
fallback does not serve, nothing told the member. The archive text is only read through
`PaperlessArchive.fetchContent`, one document at a time; Aegis stores no copy of it.

## Decision

1. **A separate derived record in the documents context.** `ArchiveTextCheck` (documents domain, table
   `document_text_checks`) keeps per document the Paperless document it measured, the number of letters and digits
   in that document's OCR text, when it was checked and, after a failure, the failed attempts and the next attempt.
   It keeps neither the text nor a copy of it and never replaces the archive's text. It is not part of the
   `Document` aggregate: writing it does not change the document's version, so it never conflicts with a member
   editing the document, and it is not exported, because it can always be measured again from the archive.
2. **Amounts.** Letters and digits of any script count; whitespace, punctuation and stray marks do not. `NONE` is no
   letter or digit, `LITTLE` fewer than 50 (`ArchiveTextCheck.LITTLE_TEXT_BELOW`; a one-page letter has several
   hundred) and `RECOGNIZED` anything more. The read model `textRecognition` of a document adds `NOT_ARCHIVED`,
   `PENDING` (archived, not measured yet, or measured for another archive document) and `UNAVAILABLE` (the text
   could not be read; `nextAttemptAt` says when it is tried again).
3. **Recorded after archival, never part of it.** The archival job commits the archived state (and the analysis
   request, ADR-0004) first, then reads the archive text once and stores the result. The read happens outside any
   transaction and every failure is caught: archival is neither delayed until the text is read nor failed or rolled
   back when it cannot be read. A failed read is stored as a failed attempt, retried after 5 minutes and then after
   twice the previous wait, at most once a day. A failed attempt never replaces a count measured for the same archive
   document; the upsert enforces this also when two checks of one document race.
4. **A bounded, resumable sweep instead of a bulk load.** The recurring job `check-archive-text` runs once a minute
   and checks at most `aegis.documents.text-check.batch-size` (default 10) due documents, most recently archived
   first: archived documents without a check (those archived before this existed), checks of another archive
   document, and failed checks whose next attempt is due. It stops at the first failure, so an unreachable archive
   is asked once per minute rather than once per document. The stored checks are its progress, so a restart simply
   continues. On a household installation with a few hundred documents the catch-up takes minutes and reads one
   document per request; nothing else reads the collection in bulk.
5. **Hint without AI.** The document list shows "Kein Text erkannt" or "Kaum Text erkannt" next to the archival
   state, and the document page explains why full-text search cannot find the document and what to do: photograph
   the page again straight from above, in good light, with the page filling the frame (for PDFs: scan it again), move
   the unreadable document to the trash, or keep it and label it by hand. "Neues Foto hochladen" opens the upload
   panel. A pending check keeps the document and list pages refreshing for up to three minutes after archival.
6. **A review card as well.** The document review producer creates one card per archive document without or with
   almost no text (`PROCESSING_FAILURE`, reason `NO_TEXT_RECOGNIZED`, fingerprint `no-text:<document>:<paperless
   id>`), so a member who uses the review inbox as the household's to-do list and receives its notice learns about
   it without opening the document. The archive document is the card's source version: renaming or labelling the
   document keeps a dismissal, while a document archived again (for example after a restore) is measured and, if
   still unreadable, reported again. The card has no command of its own; the remedy (a new capture, labels by hand,
   the trash) happens on the document, and trashing the document supersedes the card (ADR-0010). The documents
   context stays the producer: the review context reads the public `textRecognition` view only.

## Consequences

- A photo that the archive could not read is visible as such right after archival, for every member and without
  AI. With AI it can also carry an analysis card (`NO_TEXT` flag, ADR-0004); the two cards name different remedies.
- Like every review card, the card and its notices are created when the review inbox is next reconciled: in the
  background within about two minutes of the check, or earlier when a member opens the inbox (ADR-0009, issue #89).
- Each archival costs one more archive request (the document's `content` field). Existing documents are caught up
  in batches of ten per minute after the upgrade; each one without text adds a review card and its notice.
- The count measures quantity, not quality: OCR noise of 50 or more letters counts as recognized text, and a
  partly read letter (5 degrees in the #81 measurements: 14 of 60 words) is not flagged. Poor quality stays the
  AI's `POOR_OCR` flag. Paperless re-running OCR on a document (an administrator action outside Aegis) is not
  noticed until the document is archived again.
- Straightening and contrast before archival (issue #81) should make the hint rarer; the threshold and the advice
  are revisited with real household photos.
