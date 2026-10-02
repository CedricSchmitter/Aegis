# ADR-0024: Pages are split, merged, reordered, rotated and removed in one page editor that creates new documents

Status: accepted for issue #82; the backend and the portable export of derivations (schema 11) are
implemented, the app's editor screen is not yet. Date: 2026-10-02. Numbered 0024 because ADR-0023 records the AI slot hand-over that was
accepted at the same time.

## Context

A scan often holds two letters, a contract arrives separately from its annex, a page is upside down or the back of
every sheet is blank. Issue #82 asks to split one PDF into several documents and to merge several into one, without
technical help and without losing the originals. It names rotating and removing pages as a later step.

Two routes were considered in the issue:

- **The archive's own bulk edit.** The deployed Paperless-ngx 3.1.3 offers `merge`, `split`, `rotate`,
  `delete_pages` and `edit_pdf`. Its results are new archive documents created by asynchronous consume tasks outside
  the Aegis capture flow. Aegis would have to discover and adopt documents it did not create, without its stable ids,
  provenance, folder or subjects, and the archive's `delete_originals` option contradicts the rule that originals
  stay unchanged.
- **An operation in Aegis.** A documents adapter edits the pages with a PDF library and captures each result as a new
  Aegis document through the normal durable path, recording where its pages came from.

Self-hosted toolkits such as Stirling-PDF were also considered. They bring their own interface beside Aegis and share
the adoption problem of the first route.

A full PDF editor (annotations, filling and signing forms, redaction, editing text) was weighed against the
household's needs. Changing the content of an archived original contradicts the preservation rules, editing text in
a PDF is unreliable even in commercial tools, and notes already belong in Aegis rather than in the file. The owner
chose on 2026-10-02:

1. a **page editor**: page operations only, no content editing;
2. photos can be used as pages, both photos already stored as documents and new photos taken in the editor;
3. the sources simply stay as they are; the editor does not offer to move them to the trash;
4. the proposed limits below.

Filling and signing forms can follow as a separate issue; it would also create a new document and could use the
annotation editor of pdf.js.

## Decision

### One model for all page operations

An **edit plan** is an ordered list of up to 10 **outputs**. Each output is an ordered list of up to 200 **page
references** (a source, a zero-based page index, a quarter-turn rotation of 0, 90, 180 or 270 degrees) together with
the new document's title, folder and subjects. Splitting is one source distributed over several outputs, merging is
several sources in one output, reordering is the order of the references, rotating is the rotation, and removing a
page means leaving it out. A page may appear in more than one output, for example a cover letter that belongs to two
documents.

The editor never modifies a source. Every output becomes a **new document**, and every source keeps its original,
archive entry, labels, folder, tasks and contract facts. Tasks and facts that reference a source keep pointing at the
source. Moving a source to the trash stays the separate, explicit action of ADR-0010.

### Page sources

| Source | Pages are taken from |
|---|---|
| A document with a PDF original | the original: the staging copy while it is present, otherwise the original fetched from the archive |
| A document with a photo original (JPEG, PNG, WebP, HEIC; ADR-0011) | the searchable PDF the archive generates for every photo, available once the document is archived |
| New photos taken or picked in the editor | a PDF of straightened pages built in the browser and uploaded with the request (see below) |

Using the archive's rendition for photos gives one path for every photo format: Java cannot decode HEIC, and the
archive has already decoded the photo and added a text layer. Because the archive sizes the page from the photo's
72 dpi metadata (about 106 × 141 cm for a 12-megapixel photo, ADR-0011), such a page is scaled so its long side is
A4 (841.89 pt), as the browser does for scanned pages (ADR-0016). The orientation the archive applies is verified
against the VM with real phone photos and recorded in #82; the member can turn any page in the editor regardless.
A photo document that is not archived yet cannot be used and says so.

**New photos** use the scan pipeline of ADR-0016 unchanged: "Seite fotografieren" and "Bild auswählen" in the editor
straighten the pages in the browser with the same check view and corner editor, and the existing deterministic
writer builds one PDF of the new pages. It is uploaded with the request as an attached source, stored durably and
referenced by the plan like any other source. It is not a document of its own: the new pages exist only in the
outputs, and the photos are not kept after a successful request, like the paper under a flatbed scanner (ADR-0016).
At most 30 new pages per request.

Android may close the browser while the camera app is open. The plan in progress, including new pages, is kept in the
browser's IndexedDB per member, like a scan in progress (ADR-0016), and offered again after a reload. A successful
request or discarding the plan removes it. This storage is best effort.

### Sources that are refused or need a warning

A source is checked when its pages are first shown and again when the operation runs:

- a PDF that needs a password to open is refused (`pageEdit.source.encrypted`); one that opens without a password but
  carries permission restrictions is accepted, and the outputs are written without encryption;
- a PDF that cannot be parsed is refused (`pageEdit.source.unreadable`);
- a source with a digital signature or embedded files (for example the XML of a ZUGFeRD/Factur-X invoice) is
  accepted with a warning: the outputs carry neither, while the unchanged source keeps both;
- a trashed source cannot be added to a plan; a source moved to the trash after the request still contributes its
  pages, because its original is unchanged; a source whose permanent deletion is pending (ADR-0021) fails the
  operation (`pageEdit.source.gone`).

### Limits

Configurable under `aegis.documents.page-edit`: at most 10 outputs, 200 pages per output, 20 source documents and
30 new pages per request, and each output at most the upload limit (`aegis.archival.max-upload-bytes`, 50 MB). The
plan is checked against page counts before anything is stored; the size of each output is checked when it is
written, through the same validation as an upload. A violation is reported with a localized message and its code
(`pageEdit.limit.outputs`, `pageEdit.limit.pages`, `pageEdit.limit.sources`, `pageEdit.limit.newPages`,
`document.tooLarge`).

### Page previews

`GET /api/documents/{id}/pages` returns the page count, each page's size and orientation, and the warnings and
refusals above. `GET /api/documents/{id}/pages/{index}/preview` returns a JPEG of the page with a long side of about
480 px, rendered on the server with the PDF library from the page source. Rendering on the server keeps a 50 MB
original off the phone and works identically in tests and headless browsers.

A fetched source and its rendered previews are derived data: they are cached in a dedicated directory outside the
backed-up data (`aegis.documents.page-edit.cache-dir`), keyed by the document's checksum, bounded in size and age,
never exported and regenerated when missing. The ingress reconciliation never looks at this directory.

### Durable, idempotent execution

`POST /api/page-edits` (multipart: the plan as JSON and the optional PDF of new pages) carries an **operation id**
chosen by the client when the plan is started. The request is acknowledged only after:

1. the plan is validated against the current sources and limits, with authorization for every source (shared
   household access, as for reading a document);
2. the attached PDF of new pages is written and synced to `page-edits/` in ingress (a subdirectory, so the
   reconciliation never quarantines it) and recognized as a PDF from its content;
3. the new `PageEdit` aggregate of the documents context (requester, time, plan, state) and its db-scheduler job
   (`document-page-edit`, payload: operation id and schema version) are committed in one transaction.

Repeating the request with the same operation id and the same plan returns the existing operation; a different plan
under the same id is refused (`pageEdit.conflict`). The response and `GET /api/page-edits/{id}` show the state
(`REQUESTED`, `COMPLETED`, `FAILED` with a code) and the result documents, while it runs those created so far.

The job loads the sources, writes each output with the PDF library and registers it through the existing capture path
(`RegisterReceivedDocument`): staged in ingress, checksummed, registered with its classification and archival job in
one transaction. Each result's document id is derived from the operation id and the output's position (name-based
UUID), so a job that runs again after a crash finds an output it already registered and does not create a second
one. The writer is deterministic (a document id derived from the same values, no creation or modification dates), so
repeating the same plan in a new operation produces the same bytes; the checksum duplicate detection then returns the
existing document, and the result view says so, including when that document is in the trash. Unavailable archive
reads are retried with the bounded attempts of other archive jobs; refusals fail the operation with their code. The
attached PDF of new pages is deleted once the operation has finished.

### The new documents

- **Title:** each output carries the title shown in the editor, prefilled from the source in the member's language
  (for example "Stromrechnung, Teil 2") and editable. A prefilled title keeps the origin `FILENAME`, so automatic
  classification may replace it with a better one; a title the member changed is `MANUAL` and is never replaced
  (ADR-0004).
- **Folder and subjects:** prefilled from the source of the output's first page and editable per output; validated as
  for an upload. Labels are not copied.
- **Classification:** runs as for an upload when automatic analysis is enabled.
- **Original filename:** the friendly filename of the title with `.pdf`.
- **Who and when:** the requester is the uploader, the completion time the upload time, and the `PageEdit` keeps who
  requested which plan when.

### Source relationships

Migration `V2_10` adds `document_page_edits` (operation, requester, plan, state, failure, version) and
`document_derivations`: per result document, ordered page ranges (result pages, source kind `DOCUMENT` or `SCAN`,
source document, source pages, rotation) with the operation, requester and time. The document page shows "Erstellt
aus Seiten 1–3 von <source>" with a link, and the source shows the documents created from it. A trashed source is
shown as trashed. When a source is deleted permanently (ADR-0021) the range keeps its pages and shows "deleted
source"; permanently deleting a result removes its derivations.

These derived-from relationships belong to the documents context. Dossiers (#49) group documents by meaning and can
display them, but do not own them.

Derivations are household knowledge: the portable export (schema version 11) carries them by
Aegis document id, independent of archive ids, as `derivedFrom` of each created document in `data/documents.json`
and as a readable `derivedFrom` column and index note. The restore reinstates them through a public contract of the
documents context after every document is back; a range whose source did not come back (for example because the
generation lacks its original) names a deleted source, a document that already has a derivation keeps it, and files
before schema 11 restore without derivations. Page-edit operations are operational
records like AI runs and bulk runs: in the PostgreSQL recovery backup, not in the portable export.

### Library

Apache PDFBox 3.0.8 (Apache-2.0), pinned in the documents adapter module, used with temporary-file buffering so a
large original does not have to fit into memory. It loads and checks sources, imports, rotates and scales pages,
writes outputs and renders previews. Its types stay in the adapter behind ports of the documents application module.

## Consequences

- The household can split a scan of two letters, attach an annex, add a forgotten page from the camera, turn a page
  and drop blank backs in one view, on a phone, and every result is an ordinary Aegis document with its origin
  visible.
- Sources stay unchanged, so splitting a document leaves the original and its parts side by side; the member trashes
  the original by hand if wanted. Outputs lose digital signatures and embedded invoice data, which only the source
  keeps.
- A photo can only be used after it is archived, and its page quality is that of the archive's rendition.
- Page previews need the source on the Aegis server; the first preview of an archived document whose staging copy is
  gone fetches the original from the archive once.
- Content editing, annotations, forms, signatures and redaction are not part of this decision.
