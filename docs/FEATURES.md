# Features

What Aegis does today, in detail. Planned work lives in [GitHub Issues](https://github.com/CedricSchmitter/Aegis/issues); the reasoning behind each behavior is in the [architecture decisions](architecture/adr/README.md).

The web app is dark only and follows one design system: tokens with three switchable palettes, chamfered frames,
self-hosted fonts and an optional effects layer that yields to reduced motion
([ADR-0019](architecture/adr/ADR-0019-design-system.md)). Each member chooses the palette and switches the effects
in Settings; the choice is stored on the member like the language, applied after login and carried by export schema
10. On a phone the tab bar has five areas (Heute, Dokumente, Aufgaben, Verträge, Eingang), Settings, Status and Trash
open from the avatar, and a status line on Today says whether everything is OK. From 1024 px a sidebar with Upload,
the areas and the folder tree, a header with document search (Ctrl+K), the status and the inbox, and a status bar
frame the page; from 1280 px a context panel shows the selection. The document screens follow the redesign (#27): on a
phone a list of cards with search, a folder rail ending in "Ordner verwalten", a filter sheet with "Labels verwalten"
and the trash link at the end of the list; on a desktop a table whose selection shows its preview and suggestions in the
context panel, and a document view with the browser's PDF viewer. Upload and scan open in a sheet, with a drop target on
a desktop. Previews are the archive's first-page image (`GET /api/documents/{id}/thumbnail`). Today lists overdue, due
and planned tasks, the cancellation deadlines of the next 30 days with a countdown and the next seven days; a task's
checkbox completes it at once with undo, and the deadline ("Frist") and the planned work day ("Geplant") stay
separate everywhere. The task and contract lists show the selection in the context panel from 1280 px; each contract
counts down to its cancellation date and is highlighted from 30 days before it. Settings leads to Status, Trash and the
folder and label management and holds the palette, effects, language, password and logout; Login and the Android share
page use the same system. Every screen keeps text at 4.5:1 or more, shows keyboard focus (also on chamfered controls),
offers 44 px targets and stops all motion under reduced motion.

Aegis supports individual logins into one shared household (the two persons and an assistant account that
receives notices and can own tasks but is never what a document is about,
[ADR-0017](architecture/adr/ADR-0017-persons-and-the-assistant-account.md)), German/English UI, durable capture of PDFs and
photos with Paperless archival, document list/search/original access, a browsable folder tree with an Unsorted
inbox, structured labels (document type, organization, topic, tags) with faceted search, source-linked
tasks, deterministic Today, one inbox for review cards and in-app messages, a status overview, portable exports and isolated
recovery. Folders, labels and search work without AI. Archive content search follows result pages within request, time and
hit bounds; partial or unavailable searches retain title matches and show lower-bound counts and German/English
guidance (ADR-0002). Both chat retrieval modes receive this coverage, and technical gaps force provisional `PARTIAL`
evidence (ADR-0025). New uploads are classified automatically once archived
(title, type, organization, topics, evidenced issue date; conservative policy with reviewable suggestions), and
downloads and exports use readable filenames such as `2026-09-17_Telekom_Rechnung_Internet_a1b2c3d4.pdf`
([ADR-0004](architecture/adr/ADR-0004-automatic-document-classification.md)). The same run also files the
document into a matching existing folder or proposes a folder path such as `Haus / Versicherungen / Hausrat`;
folders are only created when a member accepts a proposal
([ADR-0005](architecture/adr/ADR-0005-automatic-folder-placement.md)). Existing documents are only
analyzed when a member selects them or starts "Bestand einordnen": a bulk run over the documents that were never
classified (all, Unsorted or one folder), shown first with their number and the expected cost against the daily AI
budget, worked off in small batches with progress and cancellation, resumed after a restart and spread over budget
days; its results are grouped on one page and in one inbox card
([ADR-0022](architecture/adr/ADR-0022-bulk-classification.md)). Photos from the camera or the gallery become scanned pages: the browser
finds the sheet, straightens it, gives it a scan look and assembles all pages held before saving into one PDF,
which is the original; pages can be corrected, rotated, reordered and survive a closed tab. Such a scan has no
text layer, so its page also offers the searchable PDF the archive generated from it
([ADR-0016](architecture/adr/ADR-0016-scanned-photo-pages.md)). The page editor ("Seiten bearbeiten" on a
document's page or for the documents ticked in the list) creates new documents from pages of stored documents, photos
and pages photographed in the editor with the same scan pipeline: markers between pages split, added documents
merge, and pages can be dragged or moved with buttons, rotated and left out. Titles, folder and subjects are prefilled
from the first page's source, the plan survives a closed tab, and the result is followed until the new documents
exist. Sources stay unchanged, every result is captured like an upload and its page shows where its pages came from
("Erstellt aus Seiten 1–3 von …", "Daraus erstellt" on the source), which the portable export schema 11 carries and
a restore reinstates; page previews are rendered on the server
([ADR-0024](architecture/adr/ADR-0024-page-editor.md)). Photos chosen as a file or shared from
Android (JPEG, PNG, WebP, HEIC) are accepted by their content, stay the original and are archived by Paperless into
a searchable PDF that is offered next to the photo ([ADR-0011](architecture/adr/ADR-0011-photo-originals.md)).
After archival Aegis records, without AI, whether the archive recognized text in a document; a photo held at an
angle or taken in poor light that the archive could not read is marked "Kein Text erkannt" in the list, explained
with capture advice on its page and reported in the review inbox. Documents archived earlier are caught up by a
bounded background check ([ADR-0012](architecture/adr/ADR-0012-recognized-text.md)).
The same check keeps a fingerprint of the text: a document page lists other documents that are probably the same
letter scanned or photographed again, and the review inbox shows one card per pair. Monthly records of one provider
stay apart because their numbers differ; nothing is merged or deleted, the members decide
([ADR-0028](architecture/adr/ADR-0028-near-duplicate-hints.md)).
A new installation starts with an empty tree; `ops/deploy/folder-tree.json`
and `ops/deploy/seed-folder-tree.py` create the agreed starting tree on request
([ADR-0006](architecture/adr/ADR-0006-starting-folder-tree.md)).

Contract facts are stored as observations with provenance: every value names the document it came from, the
period it is valid for and its review state, a member entering or confirming a value is the only thing that makes
it effective, and an amendment takes over from its own start date while the original stays readable
([ADR-0007](architecture/adr/ADR-0007-contract-facts-and-provenance.md)). Cancellation deadlines are
calculated from confirmed rules only - a stated deadline, or the contract end minus the confirmed notice period -
and are published for other contexts through the contracts application contract. The contracts screen shows each
observation with its provenance, the superseded history of a corrected value, reported conflicts and either the
deadline with the rule it rests on or what is missing to calculate one; automatic extraction (#23/#24) remains a
separate issue. Each confirmed cancellation deadline creates one source-linked review task with the contractual
deadline as its due date. It enters Today seven days beforehand and publishes one durable, deduplicated in-app
reminder to each active household member, including after downtime or late entry. Corrections create a distinct
occurrence while preserving member edits and completion; resolved contracts supersede only open automatic work
([ADR-0008](architecture/adr/ADR-0008-contract-cancellation-tasks-and-reminders.md)). This deterministic flow
does not use AI, a calendar or phone push.

The shared review inbox persists versioned workflow cards without copying source facts. It currently reconciles
failed archival, documents without recognized text, failed or unavailable document analysis, classification
suggestions, finished bulk classifications (one card per run instead of one per document), proposed contract facts,
fact conflicts and near-duplicate document pairs. Opening the inbox reconciles it at once, and a daily
background job makes a new card notify both members even if nobody opens the inbox; it runs as a read-only household process that can
neither decide cards nor change records. A card's notice names its reason and source, such as the document title. Retries use document application commands; confirmations and corrections stay on their source
screens. Dismissal, source resolution and notification read state remain separate, and both members share
optimistic-concurrency conflict handling. Review history is included in portable export schema 7 and restore
([ADR-0009](architecture/adr/ADR-0009-central-review-inbox.md)). Mail exceptions, task candidates and
relationship suggestions are not connected because those producers do not yet exist.

The web app has one inbox (`/inbox`, "Eingang") with two sections: "Zu prüfen" lists the review cards, "Mitteilungen"
the member's notices that no card represents (task assigned or completed, cancellation reminder, stale job, backup
failed, disk space low). A phone shows them as two segments, a desktop side by side, from 1280 px with the messages
in the context panel. Each card has one highlighted action with "Ausblenden" next to it; failed processing, a failed
backup, low disk space and a stopped job are red. Notices that announce a card, including a failed archival, are still published but not
listed a second time; opening the inbox marks them read. A successful archival publishes no notice. The navigation
badge counts open cards plus unread messages, and the old addresses `/review` and `/notices` lead to the inbox
([ADR-0009](architecture/adr/ADR-0009-central-review-inbox.md#combined-inbox-issue-83)).

Documents, tasks and contracts can be moved to a recoverable trash from their pages and restored from the trash
page by either member ([ADR-0010](architecture/adr/ADR-0010-recoverable-trash.md)). Moving to the trash asks
nothing; the message that follows offers undo. A trashed record keeps its
identity, original, metadata and links; lists, search, counts, Today, reminders, review cards and published
deadlines leave it out, while links to it still resolve and say that it is in the trash. It stays restorable at
least 30 days (`aegis.trash.recovery-window`) and is never purged automatically. A trashed contract supersedes its
open cancellation review, and restoring it reopens the same task. Paperless is not changed by trashing.

From the trash page either member can delete a record permanently after a danger dialog, one at a time or all at
once with "empty trash" below the last entry ([ADR-0021](architecture/adr/ADR-0021-permanent-deletion.md)). A document is removed by the background job
`purge-document`: its Paperless document including the Paperless trash entry, its staging copy, then the record with
its classification, analysis and placement. Until that job is done it shows as being deleted and cannot be restored; a
failed deletion is shown with a retry. Tasks and contracts are deleted at once. Tasks and contract facts that cited a
deleted document say "source deleted" (facts keep page and quote), completed cancellation reviews of a deleted
contract stay as history, and review cards and notices about the record disappear. Only who deleted what and when is
kept. Backups and export generations keep their earlier copies.

Tasks and documents have a "Verlauf" (history) at the bottom of their page
([ADR-0030](architecture/adr/ADR-0030-change-history-and-undo.md)). It is closed by default, loads when opened and lists
newest first who changed which field from what to what: a member, an automation with the member who triggered it
(contract deadlines), or an AI analysis. Tasks record creation, edits of title and note, owner, planned day, due date,
completion, reopening, trash and restore, and the changes the contract deadline automation makes; documents record
title, filename, folder and labels, including accepted suggestions, plus trash and restore. Each entry is written in
the same transaction as the change. A member's own edit of these fields offers "Rückgängig" (undo): the undo is a new
entry that restores the earlier values, and it is refused with a reason when a later change replaced one of them, the
change was already undone, or the earlier folder, label or owner no longer exists. Changes by automations and AI runs
are shown but are corrected with the regular controls instead. Records that existed before recording began say from
when changes were recorded. Deleting a record permanently also deletes its history, and the history is not part of
the portable export. Subjects, contracts and structural changes to folders and labels are not recorded yet.

A bounded AI execution boundary (ADR-0003) exists: per-workload provider, model and budgets, durable runs with
visible state, cancellation and retry, a connection check, the direct Claude API adapter and, without an API key, a
fallback to the owner's Claude subscription through headless `claude -p` on the VM.
The household chooses the model family (Opus, Sonnet, Haiku) per workload on the status page; all default to Sonnet
([ADR-0020](architecture/adr/ADR-0020-household-model-families.md)). A single question on the "Fragen" page can use another
family; the pick applies to that question only, and each answer names the family that produced it. The API uses the catalog model of the family,
the subscription always the newest one Claude Code offers. The API has a 0.25 USD household daily budget (50 USD per
day and 0.50 USD per run on the subscription) and one concurrent run. Uploads are classified one after another;
once a day's limit is used up, the remaining runs wait until midnight (household time) and then continue on their
own. Their documents read "Wartet auf KI-Budget" with the time they continue, without a failure card, and the
status page lists such runs as waiting rather than failed. A wait does not use up one of the run's attempts; a run
whose own cost budget is used up, or a daily limit too small for a single call, still fails visibly.
Both members can use the shared server-side API connection; the subscription fallback serves only the members listed
in `AEGIS_AI_SUBSCRIPTION_MEMBERS`. Without either, AI is not configured. See
[API setup](operations/DEPLOYMENT.md#claude-api) and
[subscription fallback](operations/DEPLOYMENT.md#subscription-fallback-through-claude--p).
Members can ask the household assistant questions on the "Fragen" page (`/chat`, also in the header): it researches
with read-only tools over the documents (catalog, faceted and full-text search, archived text in parts with page
markers), the contracts with their facts and the tasks, and answers with numbered sources. Every document quote is
checked against the archived text before the answer is stored, and a quote that is not there is marked. A question
runs as a `CHAT` run with visible progress, cancellation and retry; the conversation belongs to the member who asked
and the run keeps no content. Earlier conversations sit in a side panel ("Verlauf") that searches all of the member's
conversations by words in titles, questions and answers. The subscription provider runs the tool loop through its structured answer
([ADR-0025](architecture/adr/ADR-0025-household-assistant-chat.md)). The chat only reads; writing actions,
streaming and the reference-question evaluation of #25 are open. Scheduled review (#24) is not connected.
The status page first answers "is everything OK?" for the database, the archive, the backup, the disk space and AI,
in German or English words from stable codes; a broken area is red with its reason, and "Was jetzt?" says what to
check or do. Integrations that are not set up
(AI without credentials; calendar, mail, push and Telegram have no adapter yet) are not listed. After a start, provider
fallback or API-key rotation a configured AI reads "Noch nicht geprüft" until its next run or a manual connection check
([ADR-0018](architecture/adr/ADR-0018-status-overview.md)). Everything else sits in the collapsed "Technische
Details": document processing counts, the backup and disk details, the recurring background jobs, the AI workloads,
budget and runs, the exports and the running version.
The recurring background jobs (review reconciliation, recognized-text check, document reconciliation) show their last
success, next run and last failure, and a job is stale when it has not succeeded for two intervals plus 10 minutes
([ADR-0013](architecture/adr/ADR-0013-recurring-job-health.md)); the overview names a job that does not run as
planned. A watch job
checks them every five minutes and gives each member one in-app notice per incident when a job becomes stale or is
no longer scheduled ([ADR-0014](architecture/adr/ADR-0014-stale-job-notices.md)). The same watch reports the
external backup with a localized reason for a failure, and the free space of the data and backup disks; a failed or
stale backup and a disk with less than 10 % or 5 GB free each notify every member once per incident
([ADR-0015](architecture/adr/ADR-0015-backup-and-storage-health.md)).

The household uses trusted LAN HTTPS at `https://<aegis-host>`. An encrypted external Restic backup
passed isolated recovery and runs daily at 03:30; scheduled-run evidence is recorded in #39.
Aegis can be installed from Chrome on Android and receives PDFs and photos from Android's Share menu into the normal
durable upload flow ([details](operations/DEPLOYMENT.md#installable-app-and-android-share-target)); real-device
verification is tracked in #33. Mail, calendar, MCP, messenger delivery and Web Push are not connected.
For dated verification, use the relevant issues, pull requests and CI runs.

Source is maintained in this repository; the Aegis VM is the deployment environment. Private keys,
tokens, household documents and service data must not be committed.
