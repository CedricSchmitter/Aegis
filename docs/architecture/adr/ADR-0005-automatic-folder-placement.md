# ADR-0005: Automatic folder placement and proposed folder paths

Status: accepted (implemented for issue #67). Date: 2026-09-20.

## Context

[ADR-0004](ADR-0004-automatic-document-classification.md) made uploads classify themselves: labels, title, issue
date and the readable filename. It never changed a document's folder, so every upload stayed in Unsorted until a
member filed it. Issue #67 closes that gap and the remaining criterion "Assign suitable tree categories" of #23:
an uploaded document should also be filed into the matching folder of the tree
([ADR-0002](ADR-0002-document-tree-and-labels.md)), and where no suitable folder exists, Aegis should propose a
path such as `Haus / Versicherungen / Hausrat` that a member can accept.

The building blocks exist: the folder tree with single placement and explicit implied labels/subjects (ADR-0002),
the bounded AI execution boundary with source-version checks and an effect ledger (ADR-0003), and the
classification run with its conservative apply policy (ADR-0004). What was missing is context (the model never saw
the tree), an output for the placement, a policy for when a move may happen without review, and a way to create
folders from a proposal without letting a model create them.

## Decision

### One run, one call

The folder placement is decided in the existing `DOCUMENT_CLASSIFICATION` run and in the same model call as the
labels. The document text is in the prompt anyway; one answer keeps labels and folder consistent with each other,
and no second run or second call per document is introduced. The model's answer gains one optional `placement`
object; everything else about the run - budgets, retries, idempotency key, effect key, source-version check -
stays as ADR-0003 and ADR-0004 describe it.

### Catalog context

The prompt block built since PR #65 (active label vocabulary with ids, built-in keys and aliases) is extended, not
duplicated, by the folder tree:

```text
Folder tree (folderId | path | implies | documents):
- 8f1c… | Haus / Versicherungen / Hausrat | Versicherung, HOUSE | 12
```

- Full paths, the folder's stable id, the labels and subjects it passes on (including inherited ones) and the
  number of documents filed directly in it.
- Implied labels use the same token as the vocabulary block (`name` or `builtin:<key>`), so the model can match
  them literally instead of guessing.
- The order is deterministic: paths compared segment by segment through their normalized match keys, ties broken
  by folder id. The same tree therefore always produces the same block, and because the catalog comes before the
  untrusted document text, the stable part is a prefix that a provider's prompt caching can reuse. Aegis does not
  set `cache_control` yet; that is a separate change in the model adapter.
- The folder catalog is **trusted context**: it is household data, not document content. The OCR text, title and
  upload filename stay inside `UntrustedContent`, and folder names are never interpreted as labels (ADR-0002).

**Size bound and reduction.** `aegis.documents.analysis.max-catalog-folders` (default 200) bounds the block.
Measured with a representative catalog (paths of three segments with longer names, two implied labels and one
subject per folder): 40 folders add about 5,500 characters, 200 folders about 27,600; with 100 organizations in
the vocabulary the whole catalog part of the prompt stays below 40,000 characters. At roughly three characters per
token for this UUID-heavy content that is about 1,800 input tokens for a household-sized tree and about 9,200 at
the bound, so with the Sonnet 5 input price of 2.00 USD per million tokens the folder block costs about
0.004 USD per document today and at most about 0.02 USD at the bound - inside the 0.05 USD budget of one
classification run. Beyond the bound the catalog is reduced deterministically: folders whose subtree holds no
documents are dropped first (deepest and alphabetically last first), then the deepest remaining folders; a folder
is only listed when its parents are listed, and the block states how many folders were left out so the model knows
the list is incomplete.

### Output

`placement` is optional and names either an existing folder or a path that may still be missing:

| Field | Meaning |
|---|---|
| `folderId` | an existing folder from the catalog |
| `anchorFolderId` | the existing folder a proposed path hangs under (`null` = top level) |
| `newPath` | at most three folder names below the anchor |
| `confidence` | the model's own estimate |
| `evidence` | a verbatim quote from the document text |

The backend validates the placement independently of the labels: unusable names, more than three new folders, a
path that would exceed the tree depth of 8 and quotes that are not in the text are rejected.

**`null` is for unreadable documents, not for a tree without a match.** The first wording offered the model
`placement: null` whenever it was "unsure where the document belongs". Checked live on the household's own tree
(2026-09-20, a rental contract against Haus, Haus / Rechnungen, Haus / Rechnungen / Internet,
Haus / Rechnungen / Wasser, Haus / Versicherungen and Test), Sonnet 5 answered `null` in two consecutive runs: no
listed folder fitted, and giving up was the cheapest allowed answer. The instruction therefore states that a
proposal is the expected answer when no listed folder fits, allows a new top-level folder when no listed folder is
a sensible parent, and reserves `null` for a document whose text does not show what it is. With that wording the
same document proposed `Haus / Mietvertrag` anchored at the existing `Haus`. Nothing about the apply policy
changed: a proposed path is still only a suggestion.

### Resolution

Proposals are resolved in the documents context, inside the completion transaction, against the tree of that
moment - never by the classifier:

1. `folderId` wins when that folder still exists.
2. Otherwise the path is matched below the anchor segment by segment against existing siblings by normalized name
   (NFKC, trimmed, whitespace collapsed, lower case). Matching stops at the first segment without a sibling, so an
   existing folder is always reused and a sibling that differs only in case, whitespace or Unicode form can never
   be created.
3. A path whose segments all match becomes an existing-folder placement.

This makes the concurrent cases behave sensibly without a new source version: a folder **renamed** during the run
keeps its id and is still used; a folder **deleted** during the run falls back to the proposed path; a folder
**created** during the run that matches the proposal is reused instead of duplicated. Tree changes do not
invalidate a run, while changes to the document's own classification still do through the existing
`documentClassification` source version.

### Apply policy

An extension of `ClassificationPolicy` (ADR-0004 style): a document is filed **without review** only when all of

- the target is an **existing** folder,
- the document is still in Unsorted and no member has ever placed or moved it,
- the result carries no doubt flag (`POOR_OCR`, `NO_TEXT`, `CONFLICTING_INFORMATION`, `EMBEDDED_INSTRUCTIONS`,
  `MULTIPLE_DOCUMENTS`),
- the model's own confidence is at least 0.85,
- a verbatim quote from the document text supports the placement, and
- the folder implies at least one label and **every** label it implies is among the document's effective labels
  after this run.

The last condition is what makes a folder match evidence rather than a name: moving a document into
`Haus / Versicherungen` is only automatic when the document really carries the topic that folder passes on. Folders
without implied labels therefore never receive documents automatically; they only ever get a suggestion. Everything
else - a proposed path, a folder that would add labels the document does not have, a doubtful result, a document a
member already touched - is a reviewable suggestion.

**Aegis never creates folders automatically.** Accepting a proposal creates the missing folders and moves the
document in one transaction under the exclusive catalog lock, with optimistic concurrency on the anchor folder
(`expectedAnchorVersion`) and on the document's classification (`expectedVersion`). Either both happen or neither.
Members may edit the names and the parent before accepting, and one acceptance can move every other document whose
pending proposal has the same target.

### Member decisions

The placement carries its own provenance on the classification: origin (`MANUAL`, `AUTOMATIC` or none), the run
reference and confidence of an automatic move, who decided and when, and a sticky `placedByMember` flag.

| Member action | Effect on later runs |
|---|---|
| Files the document themselves | No automatic move, and no placement is proposed while it stays in that folder |
| Moves it back to Unsorted | No automatic move; a different folder may still be proposed |
| Rejects a proposal | That target is never proposed for this document again (remembered per document and target key) |
| Reverts an automatic move | Document returns to Unsorted, counts as a member decision, and that folder is remembered as rejected |

A target key identifies a proposal independently of spelling (`folder:<id>` or `path:<anchor>:<normalized names>`),
so it also groups identical pending proposals of several documents.

### Persistence

Migration `V2_4` in the documents context, additive as ADR-0001 requires:

- `document_classifications` gains `placement_origin`, `placement_confidence`, `placement_source_ref`,
  `placement_by`, `placement_at` and `placed_by_member`. Existing placements are marked as member decisions, so no
  run refiles a document the household has already sorted.
- `document_placement_suggestions` holds at most one pending proposal per document with its target key, folder or
  anchor and path, generation, confidence, quote and run reference. Foreign keys to `categories` make a proposal
  disappear with the folder it points at.
- `document_placement_rejections` remembers rejected targets per document.

### Portability

Export schema 4 adds the placement origin and the pending proposal to each document's `classification` block;
restore accepts schemas 1 to 4. A placement from an older export counts as a member decision. Remembered
rejections are local review history and are not exported; after a restore a rejected target could be proposed once
more, which the member can reject again.

## Consequences

- The classification prompt grows by the folder catalog; the measured cost is stated above and the bound keeps it
  inside the existing run budget. The catalog is the same for every document of a run batch, so prompt caching
  would remove most of it once the model adapter sets `cache_control`.
- A household that maps no implied labels to its folders sees only suggestions, never automatic moves. That is
  intended: without implied labels there is no evidence that a folder is right.
- Folder names in proposals are written by a model. They are member-editable before acceptance, validated like any
  folder name, and never interpreted as labels.
- The review inbox (#50) can list pending placement proposals through the same read model; this change only shows
  them on the document page and as a badge in the list.
- Bulk reorganization of the existing collection stays out of scope: existing documents are only analyzed when a
  member selects them (ADR-0004), and nothing moves documents in bulk automatically.
