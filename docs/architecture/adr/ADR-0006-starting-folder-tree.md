# ADR-0006: The starting folder tree is a tracked seed, not a migration

Status: accepted (implemented for issue #72). Date: 2026-09-20.

## Context

Issue #71 agreed a starting folder tree with the owner and created it in the running household on 2026-09-20
through the REST API: 33 folders, three levels at most, implied labels and subjects set, the ad-hoc folders that
had grown before removed. The naming rules behind it are now recorded in
[ADR-0002](ADR-0002-document-tree-and-labels.md#folder-naming-rules).

That left the tree as a one-off setup. Nothing in the repository described it, nothing could recreate it after a
restore into an empty database, and a second installation would start from an empty tree again. Because every
implied label on a folder's path gates `ClassificationPolicy.placesAutomatically`
([ADR-0005](ADR-0005-automatic-folder-placement.md)), an empty tree also means that automatic filing does nothing
at all: a classification run can only ever propose a path.

The built-in label vocabulary solves the same problem differently. `V2_2__document_catalog.sql` seeds 35 labels
with fixed UUIDs and a language-independent `builtinKey`; the UI renders them as `labels.builtin.<key>` in German
and English. The question issue #72 asks is whether the folder tree should ship the same way.

## Decision

### A fresh installation does not get the tree from a migration

1. **Folder names have no language-independent form.** `categories.name` is a user-authored name kept in the
   household's language (ADR-0002); there is no `builtin_key` behind it and no translation catalog entry. A
   migration would ship `Wohnen` and `Versicherungen` as product-owned text that the UI cannot render in English,
   which the repository language rule forbids. Giving folders a built-in key instead would mean a product-owned
   folder identity with its own rename, merge, export and restore semantics - a much larger change than a starting
   tree is worth, and a reversal of the user-authored name ADR-0002 chose deliberately.
2. **The life areas are the household's decision.** #71 grounded them in this household: it rents, so `Wohnen`
   means the home and not a property; `Reisen`, `Haustiere` and a per-vehicle level below `Fahrzeuge` were left out
   until documents for them actually arrive. Another household gets folders it did not ask for, and deleting 33
   unwanted folders is more work than creating the handful it wants.
3. **A wrong implication is worse than a missing folder.** A shipped default whose implied labels do not match
   another household's documents produces folders that look right and never file anything automatically. An empty
   tree is at least obviously empty.
4. **An applied migration is frozen; the tree is not.** ADR-0001 requires migrations to stay additive and never be
   edited. The tree is expected to grow as documents arrive, so every correction would need another migration,
   while installations that had meanwhile renamed a seeded folder would collect duplicates.

The built-in vocabulary stays a migration for the opposite reasons: it is language-independent, it is referenced by
fixed ID from the classifier and the export format, and it is the same on every instance by design.

### It ships as tracked data with an idempotent seeding script

- `ops/deploy/folder-tree.json` holds the tree as data: a flat list of paths with the built-in label keys and the
  subjects each folder implies. It is the tracked record of what #71 agreed, and the file a household edits before
  seeding if its life areas differ.
- `ops/deploy/seed-folder-tree.py` applies it. It prints a plan and writes nothing unless `--apply` is given; it
  matches existing folders by normalized sibling name under the same parent (NFKC, trimmed, whitespace collapsed,
  lower case - the same normalization as ADR-0002 and ADR-0005), so a second run creates nothing, and it never
  creates a sibling that differs from an existing one only by case, whitespace or Unicode form. Implied labels are
  given as built-in keys and resolved against the live vocabulary; a key that does not resolve to an active label
  fails the whole run before anything is written, because such a folder would never file a document automatically.
- `--prune` is the only way the script deletes. It removes folders the data file does not list, deepest first, and
  refuses any folder that holds documents or that still has a kept subfolder. Deleting a folder would not destroy
  documents (ADR-0002 moves them up), but a seeding script silently relocating a household's documents is not an
  acceptable side effect of running it again.
- It goes through the REST API rather than SQL, so it obeys the same authorization, sibling uniqueness, depth and
  cycle checks, version conflicts and effect rebuilds as a member working in the UI. A SQL seed would have to
  reimplement the `category_label_effects` rebuild.

### The rename guard

`ops/deploy/test_seed_folder_tree.py` runs in CI and asserts that every implied label key in the data file appears
in the built-in vocabulary seeded by `V2_2__document_catalog.sql`. Renaming or dropping a built-in key therefore
fails the build instead of producing a folder that silently never files anything. The remaining tests cover the
idempotent second run, reuse of near-duplicate sibling names, correction of drifted implications and the prune
refusals, all against an in-memory catalog so no household is contacted.

## Consequences

- A fresh installation still starts with an empty tree and the Unsorted inbox, one tracked command away from the
  same skeleton. `ops/deploy/folder-tree.json` and this ADR are what "the same skeleton" means; the household is
  expected to read the file before applying it.
- The running household keeps the tree it already has. Running the seed against it reports no changes, and the
  folders members have added since (for example `Arbeit & Bildung / Arbeit / Bewerbungen`, created by accepting a
  placement proposal) are left alone as long as `--prune` is not given.
- Deployment does not run the seed, and the application never does. Creating the default tree stays a deliberate,
  authenticated action by a member account.
- The data file carries German folder names because they are household data. The script, its tests, the built-in
  keys and this ADR stay English (AGENT.md).
- If the product later wants a translatable default tree for installations outside this household, folders need a
  built-in key in the same way labels have one. That is a separate decision and would supersede this one.
