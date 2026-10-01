# ADR-0021: Permanent deletion is an explicit member action in the trash, run as a durable job for documents

Status: accepted and implemented for issue #85. Date: 2026-10-01.

## Context

The recoverable trash ([ADR-0010](ADR-0010-recoverable-trash.md)) never deletes anything. A document uploaded by
mistake therefore stays in the household archive, together with its Paperless original, forever. Removing one took a
Paperless shell, SQL and a file deletion on the VM, which the household cannot do and which leaves no record.

Deleting a record touches every context that refers to it: tasks and contract facts cite documents, cancellation
reviews belong to contracts, review cards and notices describe records, and Paperless keeps a deleted document in its
own trash with its checksum until that trash is emptied. A Paperless call can time out after Paperless already acted.

The owner decided on 2026-09-26 and 2026-10-01:

- Deletion is manual only. Nothing is purged automatically after `restorable_until`, neither existing nor new records.
- Every member may delete any record in the trash, not only the member who trashed it.
- The confirmation is a danger dialog with a red button and no extra checkbox or typed word. It says that the action
  cannot be undone, that the original leaves Aegis and the archive, that citing tasks and contracts stay and show
  "source deleted", and that older backups still contain it.
- Contract facts that cite a deleted document keep their value, page and quote and show "source deleted".
- When a contract is deleted, cancellation reviews a member completed stay as history and show "contract deleted";
  superseded automatic reviews are deleted with it.

## Decision

1. **Only the trash, one record at a time.** `purge` is a version-checked command in each context, offered only on
   the trash page. A record outside the trash is refused with `<resource>.purge.notTrashed` (HTTP 409). There is no
   bulk purge and no purge by date.
2. **Documents are deleted by a persisted job.** The command stores the request on the trashed document
   (`purge_requested_by/at`) and schedules `purge-document` in the same transaction (HTTP 202). From then on the
   document cannot be restored (`document.purge.pending`), because the job may already have removed the original.
   Documents whose archival has not settled (`RECEIVED`, `SUBMITTED`) are refused with `document.purge.archiving`:
   a submission still in flight could create the original after the deletion.
3. **Outside work first, the record last.** The job deletes the Paperless document, empties exactly that id from the
   Paperless trash, deletes the staging copy and only then deletes the record in one transaction. The trash endpoint
   is never called without ids, which would empty the whole Paperless trash. Paperless answers "OK" even when emptying
   failed internally, so the adapter checks afterwards that the id is neither listed nor in the trash; an unknown id
   counts as deleted, so every step is safe to repeat. An archive document that another Aegis record also uses is
   kept. For a failed archival with an unrecorded consumption task, the job asks Paperless about that task, deletes a
   document it created late, and waits while the consumption still runs.
4. **Bounded retries, visible failure.** An unreachable archive or a staging file that cannot be deleted is retried
   with the archival backoff up to `aegis.archival.max-attempts`. A refusal (for example missing permission) or the
   last attempt marks the purge failed (`purge_failure_code/failed_at`); the trash shows it, and a member requests it
   again. Reconciliation reschedules a pending purge whose job row was lost.
5. **The database cascade removes what the documents context keeps.** Deleting the `documents` row removes subjects,
   classification, labels, analysis, placement suggestions and rejections and text checks through their foreign keys.
   Tasks and contracts are deleted synchronously in their own transaction; a contract's facts follow its row.
6. **What cited the record stays and says so.** Tasks and contract facts keep their stable document id. A reference
   the documents context no longer resolves is shown as deleted (`documentDeleted`), never as a dangling link; ids are
   never reused. Contract facts keep page and quote, so the confirmed value keeps its evidence. Completed cancellation
   reviews keep the contract name and are marked `contract_deleted`; the reconciliation job, scheduled atomically with
   the contract purge, deletes the other occurrences of that contract, including ones in the trash. An automatic
   review a member deleted is not created again under its stable id; a new deadline is new work and gets its review.
7. **Descriptions of the record go with it.** In the purging transaction the composition root deletes review cards
   whose source or link is the record, the notices announcing those cards and the notices that open the record. They
   only describe the record and would keep its title.
8. **A record of the deletion, without content.** `document_purges`, `task_purges` and `contract_purges` keep the id,
   who requested the deletion and when, and nothing else: no title, filename, checksum, name or facts. They make a
   repeated request after a lost response answer success (204) instead of 404, and planning tells a deleted contract
   from one that never existed. A full change history remains issue #47.
9. **Backups are not pruned.** Existing backups and export generations keep earlier copies of a deleted record; purge
   never touches them. The dialog says so.

## Consequences

- A document uploaded by mistake can be removed by either member on the phone, including its Paperless original, and
  the same bytes can be uploaded again as a new document.
- A pending or failed document purge can no longer be restored. That is intentional: whether the original still exists
  is uncertain once the job has started.
- The purge records are in the recovery backup, not in the portable export. After a portable restore, a reference to a
  purged record still shows as deleted because the record is absent; only the "who and when" is not carried over.
- Restoring an export generation that predates a purge brings the record's metadata back without its Paperless
  original; that is a recovery decision, not something the product does on its own.
