# Architecture decision records

Accepted decisions, newest last. The rules they build on are in [FOUNDATIONS.md](../FOUNDATIONS.md).

- [ADR-0001](ADR-0001-backend-modules-and-transactions.md): Backend module layout, framework versions and transaction placement
- [ADR-0002](ADR-0002-document-tree-and-labels.md): Document tree, structured labels and their shared metadata contract
- [ADR-0003](ADR-0003-bounded-ai-execution.md): Bounded, replaceable AI execution
- [ADR-0004](ADR-0004-automatic-document-classification.md): Automatic document classification and readable filenames
- [ADR-0005](ADR-0005-automatic-folder-placement.md): Automatic folder placement and proposed folder paths
- [ADR-0006](ADR-0006-starting-folder-tree.md): The starting folder tree is a tracked seed, not a migration
- [ADR-0007](ADR-0007-contract-facts-and-provenance.md): Contract facts are observations with provenance, not contract fields
- [ADR-0008](ADR-0008-contract-cancellation-tasks-and-reminders.md): Cancellation automation is a durable planning projection of confirmed contract facts
- [ADR-0009](ADR-0009-central-review-inbox.md): The central review inbox stores workflow references, not business facts
- [ADR-0010](ADR-0010-recoverable-trash.md): The trash is a recoverable state of each record, not a deletion
- [ADR-0011](ADR-0011-photo-originals.md): A photo of a paper document stays the original; the archive supplies the searchable PDF
- [ADR-0012](ADR-0012-recognized-text.md): Whether the archive recognized text is derived data of the documents context
- [ADR-0013](ADR-0013-recurring-job-health.md): The status page reports recurring job health from the scheduler's persisted state
- [ADR-0014](ADR-0014-stale-job-notices.md): Members get one notice per incident when a recurring job stops running
- [ADR-0015](ADR-0015-backup-and-storage-health.md): The backup reports a reason, disk space is measured, and members are notified once per incident
- [ADR-0016](ADR-0016-scanned-photo-pages.md): Photos from the camera and the gallery become straightened pages of one PDF, assembled in the browser
- [ADR-0017](ADR-0017-persons-and-the-assistant-account.md): Members are persons or the assistant account; "both" is no subject
- [ADR-0018](ADR-0018-status-overview.md): The status page answers "is everything OK?" first, in stable codes
- [ADR-0019](ADR-0019-design-system.md): A dark design system with tokens, palettes, chamfers and optional effects
- [ADR-0020](ADR-0020-household-model-families.md): The household chooses a model family per workload; each provider uses its newest model
- [ADR-0021](ADR-0021-permanent-deletion.md): Permanent deletion is an explicit member action in the trash, run as a durable job for documents
- [ADR-0022](ADR-0022-bulk-classification.md): Existing documents are classified in an explicitly started, bounded bulk run
- [ADR-0023](ADR-0023-ai-slot-hand-over.md): A freed AI slot is handed to the oldest waiting run
- [ADR-0024](ADR-0024-page-editor.md): Pages are split, merged, reordered, rotated and removed in one page editor that creates new documents
- [ADR-0025](ADR-0025-household-assistant-chat.md): Household assistant chat with checked citations
- [ADR-0026](ADR-0026-web-push-notifications.md): Web Push to the members' devices
- [ADR-0027](ADR-0027-household-cost-limits-per-workload.md): The household sets a daily and a session limit per workload
