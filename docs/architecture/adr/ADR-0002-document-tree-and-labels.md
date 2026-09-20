# ADR-0002: Document tree, structured labels and their shared metadata contract

Status: accepted (owner decisions 2026-09-17; implemented for issues #20 and #46). Date: 2026-09-17.
Amended 2026-09-20 with the folder naming rules agreed in #71 and written down for #72.

## Context

Issue #20 asks for a manually browsable document hierarchy with an Unsorted inbox. Issue #46 asks for
structured labels (document type, organization, topics, house/person associations, optional tags) with faceted
search, and for a later AI assignment path (#23). Both issues require one shared metadata contract so that the
household does not maintain "House" twice, and both must work without AI.

FOUNDATIONS.md listed "document title and classification" as Paperless metadata and asked this ADR to make any
additional Aegis hierarchy ownership explicit. Paperless-ngx 3.1.3 (API version 10) offers one correspondent and
one document type per document, hierarchical tags, storage paths, custom fields and automatic matching. On the
Aegis VM all of these were empty on 2026-09-17; Aegis never wrote them. Paperless cannot represent aliases,
merge history, review state, assignment origin or confidence, and its API is unavailable during an archive outage.
Paperless is not published on the LAN, so members only reach documents through Aegis.

## Decision

### Ownership

| Data | Owner | Notes |
|---|---|---|
| Original bytes, archive rendition, OCR full text | Paperless | Unchanged; Aegis reads originals and full-text hits only |
| Document title | Aegis PostgreSQL | Already edited in Aegis only; submitted to Paperless once as the initial title |
| Folder tree, folder placement | Aegis PostgreSQL | `categories`, `document_classifications` |
| Label vocabulary (types, organizations, topics, tags) incl. aliases and status | Aegis PostgreSQL | `labels`, `label_aliases`, `label_keys` |
| Label assignments incl. origin, review state, confidence, source reference | Aegis PostgreSQL | `document_labels` |
| House/person/household associations | Aegis PostgreSQL | Existing `document_subjects` on the document aggregate, reused as the subject facet |
| Effective folder implications | Derived | `category_label_effects`, `category_subject_effects`; rebuilt on every tree change |

Aegis does not write Paperless correspondents, document types, tags, storage paths or custom fields, and it does
not configure Paperless matching rules. Any value that appears there is not read and is not authoritative. A
one-way mirror into Paperless would be a separate issue and must stay a read-only view.

### Tree

- A folder (`Category`, stable UUID) has an optional parent, a user-authored name kept in its original language,
  and a version. Sibling names are unique case-insensitively; depth is limited to 8 levels; moves that would
  create a cycle are rejected. Structural changes run under an exclusive PostgreSQL advisory lock, so two
  concurrent moves cannot form a cycle, while per-folder versions still report user-visible conflicts.
- **Single placement**: every document is in exactly one folder or in Unsorted (`category_id` null). Several
  views of the same document come from labels and facets, not from multiple folder references.
- Deleting a folder never deletes documents or originals. Its documents move to the parent folder (Unsorted for
  a top-level folder) and its subfolders move to the parent. Labels and subjects that documents would lose
  because the deleted folder implied them are kept as ordinary own assignments. A sibling name conflict
  rejects the deletion until one folder is renamed.
- A folder can carry explicit **implied labels** and **implied subjects** (for example House → subject House,
  Invoices → type Invoice, Internet → topic Internet). Folder names are never interpreted as labels.

### Folder naming rules

Added on 2026-09-20 for issues #71 and #72, after a tree that had grown ad hoc turned out to file almost nothing by
itself. These are not style advice. [ADR-0005](ADR-0005-automatic-folder-placement.md) files a document without
review only when the target folder implies at least one label and **every** label it and its ancestors imply is
among the document's effective labels, so the shape of the tree decides how much filing happens automatically.

- **Sort by life area, never by document type.** A folder named `Rechnungen` duplicates the `invoice` label and
  forces a choice between the contract folder and the invoice folder for the same subject. The electricity bill and
  the electricity contract belong in the same folder and are told apart by their label.
- **No folders for states** such as `Eingang`, `Aktiv` or `Archiv`. Paper needs them because paper has no state.
  Aegis has Unsorted, processing and analysis states, tasks with due dates and review. Modelling a state as a folder
  would break single placement, because a document would have to move as its state changes.
- **A folder implies a label only when every document anywhere below it really carries that label.** An implication
  on a parent gates all of its children, so folders that merely group unrelated areas imply nothing and a leaf
  implies the one topic that defines it (`Wohnen / Energie / Strom` implies `electricity`). A folder that groups one
  coherent topic may imply it: `Versicherungen` implies `insurance`, and its children then require that topic too,
  which is right for a car insurance policy that carries both `insurance` and `car`. A folder that implies nothing
  and inherits nothing only ever receives suggestions - intended for a pure grouping folder, a defect anywhere else.
- Only implied **labels** take part in that gate, not implied subjects. `Wohnen` implying the `HOUSE` subject
  therefore stays a grouping folder that never receives a document automatically.
- **People are subjects, not folders.** `MEMBER` subjects already record whom a document concerns and do not
  restrict visibility. A per-person folder level would fragment joint documents, which is most household mail.
- At most three levels, names that still make sense in two years, no permanent `Sonstiges`, and a new area only when
  documents for it actually arrive rather than in advance.

The starting tree that follows these rules, and how a household gets it, are in
[ADR-0006](ADR-0006-starting-folder-tree.md).

### Facets and cardinality

| Facet | Values | Cardinality per document |
|---|---|---|
| Document type (`DOCUMENT_TYPE`) | Managed vocabulary | At most one own effective value; an own type overrides a folder-implied type |
| Organization (`ORGANIZATION`) | Managed vocabulary, proper names never translated or seeded | 0..n |
| Topic (`TOPIC`) | Managed vocabulary | 0..n |
| Tag (`TAG`) | Free-form vocabulary | 0..n |
| Subject | Household, House, Member (existing subjects) | 0..n |
| Folder | Tree | exactly one or Unsorted |

The **effective labels** of a document are its own effective assignments plus the labels implied by its folder
and that folder's ancestors (union for multi-valued facets; the nearest folder wins for document type). The
effective subjects are the stored subjects plus implied subjects. Implied values are derived at query time from
the effects tables and are shown as folder chips; they are never copied onto documents, so moving a document or
a folder updates them without touching own assignments. To drop an implied value, move the document or change
the folder mapping; the UI shows how many documents a folder change affects.

### Vocabulary

- Labels have stable UUIDs. Built-in types and topics carry a language-independent `builtinKey` and fixed IDs,
  so they are identical on every instance; the UI renders them through `labels.builtin.<key>` in German and
  English. Renaming a built-in label stores a custom name that is shown in both languages.
- Match keys (NFKC, trimmed, whitespace collapsed, lower case) of names and aliases are unique per facet
  (`label_keys` primary key). A free-form tag may not reuse an active name of a canonical facet and vice versa.
  Equal keys resolve to the existing label; similar names never merge automatically.
- Operations: create (optionally with a client ID for idempotent retries), rename, replace aliases, retire and
  reactivate (retired labels stay searchable but accept no new assignments), merge (assignments and folder
  mappings move to the target; the source name and aliases become target aliases; the source stays as a
  `MERGED` redirect), convert a tag into an organization or topic, and delete only when unused. Built-in labels
  can be retired but not deleted. Deleting or merging a label never deletes a document.

### Assignment provenance

Each assignment has an origin (`MANUAL` or `AUTOMATIC`), a review state, an optional model-reported confidence,
an optional source/run reference, the deciding member and a timestamp.

| Review state | Effective | Meaning |
|---|---|---|
| `SUGGESTED` | no | Automatic proposal waiting for review |
| `APPLIED` | yes | Automatic assignment applied under an explicit policy, not yet reviewed |
| `CONFIRMED` | yes | Set or confirmed by a member (manual assignments are always confirmed) |
| `REJECTED` | no | Remembered human rejection or removal of an automatic value |

The proposal use case for #23 must quote the classification version it analyzed; a stale version is refused.
It never changes a label a member decided on (confirmed, rejected or manual), never replaces a confirmed
document type and only adds `SUGGESTED` or `APPLIED` values. A member removing an automatic value turns it into
`REJECTED`, so retries and later model runs do not bring it back. Confidence is displayed as reported by the
model and is not a calibrated guarantee. Only effective values take part in filtering.

### Search semantics

`GET /api/documents` combines text, folder scope and facets:

- AND across facet groups and the folder scope; OR across selected values inside one group. Topics and tags
  offer explicit ALL matching. The UI states the active logic.
- Folder scope includes subfolders by default; `unsorted` selects documents without a folder.
- "Missing" filters select documents without an effective value of a facet (for example no document type).
- Text matches titles and, when Paperless answers, archive full-text hits. Documents that are not archived yet
  are reported as not content-indexed; an unreachable archive keeps title and metadata filtering available and
  is reported as `UNAVAILABLE`.
- Filtering, counting, sorting and pagination run in PostgreSQL through a read-model port
  (`DocumentCatalog`), not in the browser. Facet counts are computed per group with the filters of all other
  groups (disjunctive faceting) and include a missing-value count. All parameters are reflected in the page URL.
- Both members see the same collection and counts; subjects remain metadata, not access restrictions. Every
  entry point (REST, future MCP and assistant tools, export) goes through the same application services and
  `HouseholdAccess` check.

### Persistence choice

The new aggregates (`Category`, `Label`, `DocumentClassification`) and the search read model use explicit SQL
through `JdbcClient` in `documents.adapters.persistence`. Set-based rewrites (merges, folder deletion, effect
rebuilds), advisory locks and the effective-label views are clearer in SQL than through JPA entities. Aggregate
roots keep optimistic versions (`UPDATE … WHERE version = ?`, HTTP 409 `version.conflict`). The document
aggregate stays on JPA; its version is independent of the classification version, so archival progress does not
invalidate a member's pending folder or label edit.

### Migrations

The catalog migration is `V2_2` in the documents context. Because ADR-0001 numbers migrations per context, a new
migration of one context can carry a lower version than already applied migrations of another context, so Flyway
runs with `spring.flyway.out-of-order=true`. Every migration stays additive; no applied migration is edited.

### Portability

Export schema version 2 adds `data/catalog.json` (labels with aliases, status and merge redirects; folders with
implied values) and a `classification` block per document (folder and assignments with provenance). Readable
exports gain folder paths and label columns and a folder-grouped README section. Restore accepts schema 1 and 2.
It keeps existing local entities with the same ID, maps equal label keys and sibling folder names to existing
entities with a warning, applies an exported classification only to a still-unclassified document and is
idempotent. Stable Aegis IDs keep folder and label relationships intact when Paperless IDs change.
Untouched built-in vocabulary seeds adopt exported names, aliases and active/retired status; locally edited
entries keep their state. A conflicting restored name or alias keeps the local seed unchanged with a warning.

## Consequences

- FOUNDATIONS.md now lists folder tree, vocabulary and assignments as Aegis-owned. Paperless remains the owner of
  originals and full text only.
- #23 consumes the vocabulary (names, aliases, built-in keys) and the version-checked proposal use case; it
  needs no schema change for suggestions, rejections or confidence.
- Backfill, AI retrieval and the retrieval-quality evaluation of #46 remain open until an AI runtime exists
  (#21, #23, #25, #26).
- Multiple houses would need a subject extension; the current House subject is a single value.
- Folder-implied values cannot be suppressed per document; the document must move or the mapping must change.
