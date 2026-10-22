# ADR-0029: Documents and tasks keep an append-only change history with field-bound undo

Status: accepted and implemented for issue #47 (first slice). Date: 2026-10-22.

## Context

Lisa and Cedric edit the same documents and tasks, and automatic work changes them too: the contract deadline
automation creates and supersedes cancellation reviews, an AI run titles, labels and files a document. Until now only
the current state was stored. Nobody could see who changed a task's planned date or why a document left its folder,
and a wrong edit could only be repaired by remembering the earlier value. The trash ([ADR-0010](ADR-0010-recoverable-trash.md))
explicitly left a full change history to issue #47.

Issue #47 asks for an append-only business history with actor, time, action and changed fields, recorded atomically,
and for an undo of supported edits that never overwrites newer work. It rules out a generic event-sourcing rewrite and
cross-context database access.

## Decision

1. **Each context keeps its own history.** Planning stores `task_changes` (migration `V3_5`), documents stores
   `document_changes` (`V2_12`). Each row holds the record id, time, author, action, the changed fields with their
   values before and after (`jsonb`) and, for an undo, the change it reversed. The shared kernel holds only the small
   vocabulary both need: `ChangeActor`, `FieldChange` and `UndoConflictException`. No context reads the other's table.
2. **Recorded in the transaction of the change.** The application service takes a snapshot of the recorded fields
   before the change and appends the entry after saving, inside the transaction the composition root already opens.
   A rollback removes both (`ChangeHistoryIT`). A task entry carries the task's version after the change and
   `(task_id, task_version)` is unique, so one saved state is recorded at most once. Document entries are ordered by a
   sequence; their commands are version-checked on the document, its classification or its analysis, so a retried
   request fails instead of recording twice. A command that changed none of the recorded fields leaves no entry.
3. **What is recorded.** Tasks: title, note, owner, status, due date and planned date, for member commands (create,
   edit, assign, plan, change the due date, complete, reopen, trash, restore, restore from an export) and for the
   contract deadline automation (create, update from the contract, supersede, revive, contract deleted). Documents:
   title, friendly filename stem, folder and the document's own effective labels, for member commands (rename, accept a
   generated title, set the filename, move, move several, accept or revert a placement, set labels, accept or reject a
   label suggestion, accept a new-label suggestion, trash, restore) and for the result of an automatic analysis.
   Values are locale-independent: ISO dates, ids, enum names, or the member's own text. Labels are stored as the
   sorted list of label ids; the web app shows names.
4. **Authors.** `MEMBER` names the member. `AUTOMATION` names the automation (`contractDeadlines`) and the member whose
   action triggered it. `AI_RUN` names the run and the member who requested it. Nothing is invented: no reason text,
   no model reasoning, no fabricated past.
5. **Explicit baseline.** Each history table has a one-row baseline with the time recording began (the migration's
   time). Records that existed before have no earlier entries; the web app says that changes before that date were
   not recorded. A task restored from a portable export starts with an `IMPORTED` entry; a restored document starts
   without entries.
6. **Undo is a compensating command.** Only a member's own field edit can be undone. It runs through the same domain
   operations as the original command (rename, assign, plan, set labels, place, override the filename, complete,
   reopen), so their rules apply, and is recorded as a new `UNDONE` entry that names the change it reversed. Earlier
   entries are never changed. A change can be undone once; an undo is not undone again (the member edits instead).
7. **Bound to the values the change left.** An undo is refused with `history.undo.changedSince` (HTTP 409, listing
   the fields) when a later change replaced a value this change set. A later change of another field does not stand
   in the way. Labels are compared one by one: the undo is refused only when a label this change added is gone again
   or one it removed is back; it removes what this change added and brings back what it removed, keeping labels added
   since. A task undo also carries the task version the member saw (`version.conflict` otherwise); a document's
   fields live in three aggregates with separate versions, so its undo is bound to the field values only, and saving
   still checks each aggregate's version. A folder or label that no longer exists, or an owner who left, is refused
   (`history.undo.folderGone`, `labelGone`, `ownerInactive`). A record in the trash is restored first.
8. **Deadlines stay separate.** Undoing a planned date changes only the planned date, and the reverse; the due date of
   a cancellation review stays managed by its contract and cannot be changed by an undo either.
9. **AI results are corrected through their review.** An analysis entry is shown but not undone from the history:
   its review controls (revert the title, take back the placement, reject the label) already remember the member's
   decision so a later run does not apply it again, and they stay the one way to correct it. An undo of a member's
   label edit uses the same rule: an automatic label it removes is remembered as rejected.
10. **Authorization and retention.** Both members read every record's history, like the record itself; only members
    undo. Entries live as long as their record and leave with it through a foreign-key cascade when it is deleted
    permanently ([ADR-0021](ADR-0021-permanent-deletion.md)). History is part of the recovery backup and not of the
    portable export; a portable restore starts a new history.

## Consequences

- Each member sees who changed what and when on a task and on a document page, and can take back their own or the
  other member's edit without losing a later one.
- Not recorded yet: document subjects, structural changes that touch many documents at once (a folder deleted moves
  its documents, labels merged or converted), contracts and their facts. A later undo still notices such a change
  through its field comparison. These are follow-ups in issue #47.
- The portable export does not carry history, so a migration to another installation keeps the records but starts
  their history anew.
