# ADR-0010: The trash is a recoverable state of each record, not a deletion

Status: accepted and implemented for issue #48. Date: 2026-09-22. Permanent deletion from the trash is decided in
[ADR-0021](ADR-0021-permanent-deletion.md) (issue #85).

## Context

Until now nothing in Aegis could be deleted through the product. Removing a wrongly uploaded document took three
manual steps on the VM (Paperless shell, SQL, the ingress file), and a test contract created during a live check
stayed in the household data because no path existed at all. Lisa and Cedric need to correct such mistakes on
their phones without technical help, and without risking data that cannot be brought back.

Documents, tasks and contracts belong to three bounded contexts. Tasks and contract facts reference documents,
automatic cancellation reviews reference contracts, and review cards, notices, search, exports and scheduled jobs
read all of them. Deleting rows would sever those links or cascade into them; a separate "trash" context that
copies records would create a second source of truth.

The owner decided on 2026-09-22: documents, tasks and contracts can be trashed; a trashed record stays restorable
for at least 30 days; this change implements the trash only, and permanent deletion is a separate decision.

## Decision

1. **Trash is a lifecycle marker on the aggregate.** `Document`, `Task` and `Contract` each carry an optional
   `Trash` value (who, when, restorable until) from the shared kernel. The row, its identity, the original, the
   Paperless archive link, labels, folder, subjects, facts, relationships and version history stay untouched.
   Each context owns its own trash state; no context reads another context's trash columns.
2. **Recovery window.** `aegis.trash.recovery-window` (default 30 days, at least one day) fixes
   `restorable_until` when a record is trashed, so a later configuration change never shortens a promise already
   shown. Nothing is purged automatically: a record also stays restorable after that date until a member deletes it
   permanently from the trash ([ADR-0021](ADR-0021-permanent-deletion.md)). The product text says "restorable at
   least until".
3. **Visibility.** Ordinary lists, search (title and Paperless full text), folder and label counts, Today, the
   document page's task and contract lists, the review inbox producers, the assistant's `search_documents` tool
   and published contract deadlines exclude trashed records. Reading a record by its id still works and returns
   its trash state, so notices, task sources and fact sources resolve and can show "in the trash" with a restore
   action. Each context offers a `trashed` listing; the web app's trash page combines the three.
4. **Commands.** `moveToTrash` and `restoreFromTrash` are version-checked application commands in each context,
   authorized like every other household command. Repeating either for a record that is already in the requested
   state returns it unchanged, so a retry after a lost response is harmless. Member changes to a trashed record
   are refused with `<resource>.trashed` (HTTP 409); the member restores first. New links to a trashed document
   (a task source, a stated contract fact) are refused the same way.
5. **Automatic work.** Archival of a trashed upload continues so the original reaches Paperless. Automatic
   analysis waits: the archive trigger skips trashed documents, and restoring an archived document fires the
   trigger again. A run already in flight may still complete; its result stays with the hidden document. Reminders
   are not published for trashed tasks, and planning does not recreate an automatic review a member trashed.
   Restoring such a review reconciles it with its contract at once, so it regains its reminder, or is superseded
   when the contract was resolved or trashed in the meantime.
6. **Contracts and their reviews.** A trashed contract publishes no deadline, so the existing reconciliation
   supersedes its open cancellation review (ADR-0008), including one waiting in the trash. On restore, the same
   reconciliation reopens that superseded occurrence (same task id, owner, note and planned date) instead of
   leaving no open review; this also covers a correction that returns to an earlier deadline. A completed review
   is never reopened.
7. **Paperless is not touched.** Trash and restore change nothing in Paperless; the archived original stays where
   it is. The direct Paperless interface is an administrator tool and is not the household's trash.
8. **Duplicates.** The checksum stays unique. Uploading the same bytes again answers with the trashed document
   (marked as trashed) instead of creating a copy; the web app points the member to the trash to restore it.
9. **Structure changes while trashed.** Deleting a folder moves its trashed documents to the parent like visible
   ones, so a restored document never points at a missing folder. A label used only by trashed documents cannot
   be deleted (`label.inUseInTrash`); merging and retiring stay available. A task whose owner is no longer an
   active member is restored unassigned.
10. **Portability.** Export schema 8 carries the trash record of documents, tasks and contracts, and the readable
    CSV files and index mark trashed records. Restore keeps the trash record, including its original promise;
    files before schema 8 restore as not trashed. Trash and restore never prune backups.

## Consequences

- An accidental deletion is undone in the app, by either member, with all data and links intact.
- Records in the trash still occupy storage and count in exports and backups; that is intended until permanent
  deletion exists.
- Who trashed a record and when is kept while it is trashed. After a restore only the record's current state
  remains in the record itself; the change history of
  [ADR-0029](ADR-0029-change-history-and-undo.md) records the trash and the restore.
- Permanent deletion, including the Paperless original and its own trash, is a separate explicit action
  ([ADR-0021](ADR-0021-permanent-deletion.md)).
