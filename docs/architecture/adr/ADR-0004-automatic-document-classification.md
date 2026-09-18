# ADR-0004: Automatic document classification and readable filenames

Status: accepted (implemented for issue #23). Date: 2026-09-18.

## Context

Issue #23 asks that new uploads are organized automatically once they are durably stored and archived with OCR,
and that both members can follow, correct, accept or reject the results and restart failed processing. It also
asks for deterministic, human-readable download and export names. The building blocks exist: the bounded AI
execution boundary of [ADR-0003](ADR-0003-bounded-ai-execution.md) (durable runs, budgets, retries, cancellation,
source-version check, effect ledger) and the vocabulary, provenance and review states of
[ADR-0002](ADR-0002-document-tree-and-labels.md). Issue #59 found that a reused idempotency key could return a run
for different work; this had to be fixed before automatic jobs were connected. Model selection per workload is
tracked separately (#64); all workloads use Sonnet 5.

## Decision

### States and ownership

Storage, archival/OCR and AI processing are three separate visible states:

| State | Owner | Values |
|---|---|---|
| Capture | Documents (`documents`) | saved once the original is in ingress and the record committed |
| Archival/OCR | Documents (`ProcessingInfo`) | `RECEIVED`, `SUBMITTED`, `ARCHIVED`, `FAILED` |
| Analysis | Documents (`document_analyses`) plus the run state from the assistant context | `NOT_REQUESTED`, `WAITING_FOR_ARCHIVE`, `PENDING`, `CLASSIFIED`, `NEEDS_REVIEW`, `FAILED`, `UNAVAILABLE` |

The documents context owns a `DocumentAnalysis` aggregate per analyzed document: a request **generation**, who
requested it and why (upload or member), the generated values of the latest finished run (title, issue date,
short topic, correspondent, doubt flags, new-name and subject suggestions) with their review decisions, the
member's filename override and a bounded history of earlier generated values. Run progress is not copied: while a
generation is requested, the view reads the latest run for the key `document-classification:<id>:g<generation>`
through `AiRunStatusQuery` (a port implemented in the composition root), so a failed, cancelled or waiting run is
shown truthfully. `PENDING` covers queued, running and waiting-for-retry runs; `FAILED` shows the run's failure
category (for example `BUDGET_EXHAUSTED`, `PROVIDER_UNAVAILABLE`, `STALE_SOURCE`). `NEEDS_REVIEW` means a finished
result left suggestions (labels, title, new names or subjects) for a member.

### Trigger and durability

- An upload made while automatic analysis is on (`aegis.documents.analysis.automatic`, default on) records the
  analysis as `WAITING_FOR_ARCHIVE` in the capture transaction. Restores never do, and documents that existed
  before this feature have no analysis: the existing collection is only analyzed when a member selects documents
  (one at a time or up to ten at once). There is no automatic bulk run over the collection.
- Paperless reports a finished consumption only after OCR, so `ARCHIVED` is the readiness signal. The archival
  transaction that records `ARCHIVED` also requests the run through the documents port `AnalysisRuns`; the AI run
  and its db-scheduler job are created in that same transaction (ADR-0003). A repeated archival event finds the
  analysis already requested and starts nothing.
- The run is requested on behalf of the uploader. If AI is not configured, the workload has no price or the
  uploader is no longer a member, `RequestAiRun.tryRequest` reports this without throwing, the analysis becomes
  `UNAVAILABLE` with the reason, and archival commits normally. Capture, archival, search, folders, labels and
  tasks never depend on the model.
- Every member request (first time, retry after a failure, reprocessing) starts a new generation and therefore a
  new key and effect key. A request is refused with `analysis.pending` while a run of the current generation is
  active. Retrying a failed run from the status page reuses its key and sources unchanged.

### Run, idempotency and effects

- The workload handler `DocumentClassificationHandler` (assistant context, `DOCUMENT_CLASSIFICATION`) makes one
  structured model call without tools. Sources are the classification version and the analysis generation of
  the document; `prepare` stops before any model call when the classification already changed.
- The prompt lists the active vocabulary (ids, facets, built-in keys or names, aliases), the active members'
  display names and ids, the document's current labels and the archive's OCR text (at most
  `aegis.documents.analysis.max-text-chars`, default 20,000, cut visibly). Title, upload filename and text are
  framed with `UntrustedContent`; the system prompt tells the model to report instructions aimed at an AI as the
  `EMBEDDED_INSTRUCTIONS` flag and to classify by content. The model cannot write anything: its answer only feeds
  `AutomaticClassification.assess` and `apply`.
- The effect key is `document-classification:<id>:g<generation>`. The completion transaction checks that the
  classification version and the generation are unchanged (`sourcesCurrent`), claims the effect in the ledger and
  applies the assessment through `AutomaticClassification.apply`, which refuses a stale generation or
  classification version again. A late answer of an older generation, a revived job or a duplicate delivery
  therefore never writes twice or over newer data.
- **#59:** the idempotency key names one unit of work: workload, analyzed sources with their versions and
  parameters. `AiRunService` rejects a request whose key belongs to a run (active or finished, including with
  `reprocess`) for different work with `ai.run.keyConflict`; requests are serialized by the existing advisory
  lock, so concurrent conflicting requests cannot both create runs. The requesting member is deliberately not part
  of the identity, so both members asking for the same work share one run. The connection check key now includes
  the workloads it covers.

### Application policy

Model confidence is the model's own estimate, not a calibrated probability, so it is only one of several
conditions (`ClassificationPolicy`):

| Output | Applied without review when | Otherwise |
|---|---|---|
| Document type, organization, topic | the value resolves to an active vocabulary entry (by id, name or alias, then merge redirect), the confidence is at least 0.85, a verbatim quote from the text supports it, and no doubt flag is set; at most three topics | `SUGGESTED` label (ADR-0002); retired or wrong-facet ids are dropped |
| Unknown organization or topic | never created automatically | offered as a new-name suggestion when quoted and at least 0.6; accepting creates or reuses the label and assigns it as a member decision |
| Title | a filename-derived title is still unchanged when the result is applied, and no doubt flag is set; applied at most once | suggestion; a title a member chose or a title generated earlier is never replaced |
| Issue date | its quote is document text and spells out that date (German or English notations), not in the future beyond one year and not before 1900 | `UNVERIFIED`, `CONFLICTING` or `UNKNOWN`; never used in names |
| Subjects (house, household, member) | never | suggestion with quote; accepting adds the subject |

Doubt flags (`POOR_OCR`, `NO_TEXT` including an empty OCR text, `CONFLICTING_INFORMATION`,
`EMBEDDED_INSTRUCTIONS`, `MULTIPLE_DOCUMENTS`) turn every value into a suggestion. Member decisions survive later
runs: confirmed, rejected and manual labels are never changed (ADR-0002), an earlier automatic suggestion is not
upgraded by a later run, a rejected title, name or subject is not proposed again, and the filename override is
never touched by a run. Reverting an applied title restores the previous title as a member decision.

### Readable filenames

`FriendlyFilename` renders `<document-date>_<correspondent>_<document-type>_<short-topic>_<stable-id>.<extension>`,
for example `2026-09-17_Telekom_Rechnung_Internet_a1b2c3d4.pdf`:

- The date is only the evidenced issue date of a result without doubt flags; organization and type are the
  document's **effective** labels (own applied or confirmed values, or a folder-implied type); the short topic comes
  from a result without doubt flags. Unknown parts are left out. Without any part, the display title and then the
  word `document` describe the file.
- Built-in types and topics use one household naming language (`aegis.documents.analysis.filename-language`,
  default `DE`), never the viewer's UI language, so switching languages does not rename files. Renaming or merging
  a label changes the names of its documents, which is intended.
- Components are NFKC-normalized; control, format and bidi characters, path and reserved characters, IBANs and
  tokens with five or more digits are removed; separators are collapsed and the descriptive part is bounded to
  150 UTF-8 bytes. The stable id is the first eight hex digits of the document id; the extension comes from the
  verified media type. Every name ends in `_<id>.<ext>`, so no name equals a reserved Windows device name or ends
  in a dot or space.
- Within one export, names that still collide case-insensitively use the full document id.
- A member can set a stem (`PUT /api/documents/{id}/filename`); the stable id and extension are always added, and
  clearing it returns to the generated name. Digits chosen by a member are kept.
- Downloads send `Content-Disposition` with an ASCII `filename` fallback and the RFC 5987 `filename*` UTF-8
  name; the original bytes are served unchanged and checked against the stored checksum.

Four names stay distinct: the immutable **original filename** of the upload, the **display title**, the
**friendly filename** for downloads and exports, and the **archive storage path** inside Paperless, which Aegis
neither configures nor renames (ADR-0002: Aegis writes no Paperless storage paths or custom fields).

### Portability

Export schema 3 adds per document the title origin, the friendly filename (the original is stored under it in
`documents/originals/`) and the analysis (generated values with run reference and model, review decisions,
filename override, history). Readable CSV gains the friendly filename, the evidenced document date and the
analysis status. Restore accepts schemas 1 to 3; it restores the analysis only while the local document has none
of its own, never restores a running request and never starts a model run. Titles from exports before schema 3
count as chosen by a member.

## Consequences

- Each automatic upload costs one Sonnet 5 call within the classification run budget (0.05 USD) and the household
  daily limit (0.25 USD). When the daily limit is reached, further analyses fail visibly as `BUDGET_EXHAUSTED`
  and can be requested again later; they are not retried automatically.
- A member editing labels or the folder while a run is working makes that run fail as `STALE_SOURCE`; the member
  can request it again.
- The document list shows analysis badges for waiting, running, review, failed and unavailable states; a central
  review inbox is issue #50.
- Automatic subject associations and new labels always need a member; there is no auto-create policy yet.
- Evidence matching is literal after folding width, case, whitespace and soft hyphens; poor OCR therefore tends to
  produce suggestions rather than applied values, which is intended.
