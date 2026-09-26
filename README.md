# Aegis

A private household workspace for shared documents and tasks, built with Java/Spring Boot and React.

## Start here

- [Shared engineering instructions](AGENT.md): canonical rules for contributors, Codex, and Claude.
- [Architecture foundations](docs/architecture/FOUNDATIONS.md), [ADR-0001](docs/architecture/adr/ADR-0001-backend-modules-and-transactions.md) (DDD, hexagonal modules, versions, transactions), [ADR-0002](docs/architecture/adr/ADR-0002-document-tree-and-labels.md) (folder tree, labels, faceted search), [ADR-0003](docs/architecture/adr/ADR-0003-bounded-ai-execution.md) (bounded, replaceable AI execution), [ADR-0004](docs/architecture/adr/ADR-0004-automatic-document-classification.md) (automatic classification, readable filenames), [ADR-0005](docs/architecture/adr/ADR-0005-automatic-folder-placement.md) (automatic folder placement and proposed paths), [ADR-0006](docs/architecture/adr/ADR-0006-starting-folder-tree.md) (tracked starting tree), [ADR-0007](docs/architecture/adr/ADR-0007-contract-facts-and-provenance.md) (confirmed contract facts), [ADR-0008](docs/architecture/adr/ADR-0008-contract-cancellation-tasks-and-reminders.md) (durable cancellation tasks and reminders), [ADR-0009](docs/architecture/adr/ADR-0009-central-review-inbox.md) (central review inbox), [ADR-0010](docs/architecture/adr/ADR-0010-recoverable-trash.md) (recoverable trash), [ADR-0011](docs/architecture/adr/ADR-0011-photo-originals.md) (photos as document originals), [ADR-0012](docs/architecture/adr/ADR-0012-recognized-text.md) (whether the archive recognized text), [ADR-0013](docs/architecture/adr/ADR-0013-recurring-job-health.md) (recurring job health on the status page), [ADR-0014](docs/architecture/adr/ADR-0014-stale-job-notices.md) (notices when a recurring job stops running), [ADR-0015](docs/architecture/adr/ADR-0015-backup-and-storage-health.md) (backup failure reasons, disk space and their notices), [ADR-0016](docs/architecture/adr/ADR-0016-scanned-photo-pages.md) (scanned photo pages as one PDF), [ADR-0017](docs/architecture/adr/ADR-0017-persons-and-the-assistant-account.md) (persons and the assistant account; no subject "both"), [ADR-0018](docs/architecture/adr/ADR-0018-status-overview.md) (status overview first, stable codes, technical details collapsed) and [ADR-0019](docs/architecture/adr/ADR-0019-design-system.md) (dark design system: tokens, palettes, chamfers, self-hosted fonts, optional effects).
- [GitHub Issues](https://github.com/CedricSchmitter/Aegis/issues): feature requests, open work, acceptance criteria and dependencies.
- [Household access](docs/operations/ACCESS.md): accounts and trusted LAN HTTPS.
- [Deployment](docs/operations/DEPLOYMENT.md), [export, restore drill and backup](docs/operations/BACKUP.md), [VM access](docs/VM-ACCESS.md).

AGENTS.md and CLAUDE.md reference AGENT.md to avoid competing instruction sets.

## Repository layout

```text
backend/        Maven multimodule Spring Boot backend (Java 21): shared-kernel, identity, documents, contracts,
                planning, notifications, review (shared review inbox), operations, portability,
                assistant (AI runs), bootstrap, architecture-tests
frontend/       React/TypeScript/Vite web app (German/English), served by the backend from one origin
api/            Committed OpenAPI contract; the TypeScript client is generated from it
e2e/            Playwright browser smoke tests of core household workflows
deploy/         Dockerfile, Compose stack, Caddyfile for the Aegis VM
ops/            Deployment, backup and restore-drill scripts; VM bootstrap; the tracked starting
                folder tree and its seeding script
fixtures/       Synthetic test material only (known checksum)
docs/           Architecture decisions and operating procedures
```

## Build and verify

Backend (Maven Wrapper downloads Maven 3.9.9; Java 21 required):

```bash
cd backend
./mvnw verify                 # unit, architecture and PostgreSQL/Testcontainers integration tests (Docker needed)
./mvnw -DskipITs verify       # without Docker: unit and architecture tests only
./mvnw -Daegis.openapi.export=true -pl bootstrap -am verify   # refresh api/openapi.json from the running backend
```

The Claude API live test is skipped unless enabled explicitly. It costs a few cents and reads the key from a file
outside the repository (ADR-0003):

```bash
AEGIS_AI_LIVE_TEST=true AEGIS_ANTHROPIC_API_KEY_FILE=/path/to/anthropic-api-key \
  ./mvnw -pl assistant/adapters -am -DskipITs test -Dtest=AnthropicGatewayLiveTest -Dsurefire.failIfNoSpecifiedTests=false
```

The Claude Code host runner (subscription fallback) has hermetic tests with a stand-in CLI; process and socket tests
run only on Linux:

```bash
python3 -m unittest discover -s ops/claude-runner -p 'test_*.py'
```

Frontend (Node 24):

```bash
cd frontend
npm ci
npm run generate:api          # regenerate src/api/schema.d.ts from ../api/openapi.json
npm run check                 # types, lint, locale catalog and helper tests
npm run build                 # production bundle in frontend/dist
npm run dev                   # dev server with API proxy to http://localhost:8080
python scripts/generate-icons.py   # regenerate public/icons from icons/aegis-icon-source.webp (needs Pillow)
```

Run the backend locally against a PostgreSQL and Paperless of your own by setting `AEGIS_DB_URL`,
`AEGIS_DB_USERNAME`, `AEGIS_DB_PASSWORD`, `AEGIS_PAPERLESS_URL`, `AEGIS_PAPERLESS_USERNAME`,
`AEGIS_PAPERLESS_PASSWORD`, `AEGIS_INGRESS_DIR`, `AEGIS_EXPORTS_DIR` and `AEGIS_MEMBERS_FILE`
(see `backend/bootstrap/src/main/resources/application.yml`), then `java -jar backend/bootstrap/target/aegis-bootstrap-*.jar`.

Browser smoke test against a deployed stack:

```bash
cd e2e && npm ci && npm run install:browsers
AEGIS_BASE_URL=https://192.168.2.159 AEGIS_USER_A=lisa AEGIS_PASSWORD_A=... AEGIS_USER_B=cedric AEGIS_PASSWORD_B=... npm test
```

CI runs on pull requests only, and each workflow only when its paths change: `backend.yml` (Maven verify with
Testcontainers), `frontend.yml` (checks, build and generated-client drift check), `ops.yml` (Python tests of the
ops scripts) and `smoke.yml` (image build and browser smoke against the compose stack, automatically for `deploy/`
and `e2e/` changes, otherwise started by hand with `gh workflow run smoke.yml --ref <branch>`). Every workflow can
also be started by hand. CI needs no private VM access and no live credentials.

When GitHub Actions is unavailable (for example with used-up minutes), the same three jobs run for a commit on VM 102
from the PC:

```bash
sh ops/ci/vm-checks.sh <commit-ish> [backend] [frontend] [smoke]
```

The script ships the commit as a git archive and runs Maven verify with Testcontainers, the frontend checks with the
generated-client drift check, and the browser smoke against its own compose stack with real Paperless. That stack is a
separate compose project (`aegis-ci`) with synthetic credentials, its own data directory, no published port and no
access to the Claude runner. The frontend and the browser test share the Playwright image (about 2.5 GB, kept on the
VM); the CI image and its build cache are removed after each run. A green run deletes its directory. The log stays in
`/srv/aegis/source/ci-runs/<sha>.log`; a failed run keeps its reports next to it. Post the summary in the PR, because
GitHub shows no check for such a run.

## Current state

The web app is dark only and follows one design system: tokens with three switchable palettes, chamfered frames,
self-hosted fonts and an optional effects layer that yields to reduced motion
([ADR-0019](docs/architecture/adr/ADR-0019-design-system.md)). Each member chooses the palette and switches the effects
in Settings; the choice is stored on the member like the language, applied after login and carried by export schema
10. On a phone the tab bar has five areas (Heute, Dokumente, Aufgaben, Verträge, Eingang), Settings, Status and Trash
open from the avatar, and a status line on Today says whether everything is OK. From 1024 px a sidebar with Upload,
the areas and the folder tree, a header with document search (Ctrl+K), the status and the inbox, and a status bar
frame the page; from 1280 px a context panel shows the selection. The document screens follow the redesign (#27): on a
phone a list of cards with search, a folder rail ending in "Ordner verwalten", a filter sheet with "Labels verwalten"
and the trash link at the end of the list; on a desktop a table whose selection shows its preview and suggestions in the
context panel, and a document view with the browser's PDF viewer. Upload and scan open in a sheet, with a drop target on
a desktop. Previews are the archive's first-page image (`GET /api/documents/{id}/thumbnail`).

Aegis supports individual logins into one shared household (the two persons and an assistant account that
receives notices and can own tasks but is never what a document is about,
[ADR-0017](docs/architecture/adr/ADR-0017-persons-and-the-assistant-account.md)), German/English UI, durable capture of PDFs and
photos with Paperless archival, document list/search/original access, a browsable folder tree with an Unsorted
inbox, structured labels (document type, organization, topic, tags) with faceted search, source-linked
tasks, deterministic Today, one inbox for review cards and in-app messages, a status overview, portable exports and isolated
recovery. Folders, labels and search work without AI. New uploads are classified automatically once archived
(title, type, organization, topics, evidenced issue date; conservative policy with reviewable suggestions), and
downloads and exports use readable filenames such as `2026-09-17_Telekom_Rechnung_Internet_a1b2c3d4.pdf`
([ADR-0004](docs/architecture/adr/ADR-0004-automatic-document-classification.md)). The same run also files the
document into a matching existing folder or proposes a folder path such as `Haus / Versicherungen / Hausrat`;
folders are only created when a member accepts a proposal
([ADR-0005](docs/architecture/adr/ADR-0005-automatic-folder-placement.md)). Existing documents are only
analyzed when a member selects them. Photos from the camera or the gallery become scanned pages: the browser
finds the sheet, straightens it, gives it a scan look and assembles all pages held before saving into one PDF,
which is the original; pages can be corrected, rotated, reordered and survive a closed tab. Such a scan has no
text layer, so its page also offers the searchable PDF the archive generated from it
([ADR-0016](docs/architecture/adr/ADR-0016-scanned-photo-pages.md)). Photos chosen as a file or shared from
Android (JPEG, PNG, WebP, HEIC) are accepted by their content, stay the original and are archived by Paperless into
a searchable PDF that is offered next to the photo ([ADR-0011](docs/architecture/adr/ADR-0011-photo-originals.md)).
After archival Aegis records, without AI, whether the archive recognized text in a document; a photo held at an
angle or taken in poor light that the archive could not read is marked "Kein Text erkannt" in the list, explained
with capture advice on its page and reported in the review inbox. Documents archived earlier are caught up by a
bounded background check ([ADR-0012](docs/architecture/adr/ADR-0012-recognized-text.md)).
A new installation starts with an empty tree; `ops/deploy/folder-tree.json`
and `ops/deploy/seed-folder-tree.py` create the agreed starting tree on request
([ADR-0006](docs/architecture/adr/ADR-0006-starting-folder-tree.md)).

Contract facts are stored as observations with provenance: every value names the document it came from, the
period it is valid for and its review state, a member entering or confirming a value is the only thing that makes
it effective, and an amendment takes over from its own start date while the original stays readable
([ADR-0007](docs/architecture/adr/ADR-0007-contract-facts-and-provenance.md)). Cancellation deadlines are
calculated from confirmed rules only - a stated deadline, or the contract end minus the confirmed notice period -
and are published for other contexts through the contracts application contract. The contracts screen shows each
observation with its provenance, the superseded history of a corrected value, reported conflicts and either the
deadline with the rule it rests on or what is missing to calculate one; automatic extraction (#23/#24) remains a
separate issue. Each confirmed cancellation deadline creates one source-linked review task with the contractual
deadline as its due date. It enters Today seven days beforehand and publishes one durable, deduplicated in-app
reminder to each active household member, including after downtime or late entry. Corrections create a distinct
occurrence while preserving member edits and completion; resolved contracts supersede only open automatic work
([ADR-0008](docs/architecture/adr/ADR-0008-contract-cancellation-tasks-and-reminders.md)). This deterministic flow
does not use AI, a calendar or phone push.

The shared review inbox persists versioned workflow cards without copying source facts. It currently reconciles
failed archival, documents without recognized text, failed or unavailable document analysis, classification
suggestions, proposed contract facts and fact conflicts. Opening the inbox reconciles it at once, and a daily
background job makes a new card notify both members even if nobody opens the inbox; it runs as a read-only household process that can
neither decide cards nor change records. A card's notice names its reason and source, such as the document title. Retries use document application commands; confirmations and corrections stay on their source
screens. Dismissal, source resolution and notification read state remain separate, and both members share
optimistic-concurrency conflict handling. Review history is included in portable export schema 7 and restore
([ADR-0009](docs/architecture/adr/ADR-0009-central-review-inbox.md)). Mail exceptions, task candidates and
duplicate/relationship suggestions are not connected because those producers do not yet exist.

The web app has one inbox (`/inbox`, "Eingang") with two sections: "Zu prüfen" lists the review cards, "Mitteilungen"
the member's notices that no card represents (task assigned or completed, cancellation reminder, stale job, backup
failed, disk space low). Notices that announce a card, including a failed archival, are still published but not
listed a second time; opening the inbox marks them read. A successful archival publishes no notice. The navigation
badge counts open cards plus unread messages, and the old addresses `/review` and `/notices` lead to the inbox
([ADR-0009](docs/architecture/adr/ADR-0009-central-review-inbox.md#combined-inbox-issue-83)).

Documents, tasks and contracts can be moved to a recoverable trash from their pages and restored from the trash
page by either member ([ADR-0010](docs/architecture/adr/ADR-0010-recoverable-trash.md)). A trashed record keeps its
identity, original, metadata and links; lists, search, counts, Today, reminders, review cards and published
deadlines leave it out, while links to it still resolve and say that it is in the trash. It stays restorable at
least 30 days (`aegis.trash.recovery-window`); permanent deletion is not implemented (#85), so nothing is purged. A
trashed contract supersedes its open cancellation review, and restoring it reopens the same task. Paperless is not
changed by trashing.

A bounded AI execution boundary (ADR-0003) exists: per-workload provider, model and budgets, durable runs with
visible state, cancellation and retry, a connection check, the direct Claude API adapter and, without an API key, a
fallback to the owner's Claude subscription through headless `claude -p` on the VM.
All workload profiles default to Sonnet 5 with a 0.25 USD household daily budget for the API (50 USD per day and
0.50 USD per run on the subscription) and one concurrent run.
Both members can use the shared server-side API connection; the subscription fallback serves only the members listed
in `AEGIS_AI_SUBSCRIPTION_MEMBERS`. Without either, AI is not configured. See
[API setup](docs/operations/DEPLOYMENT.md#claude-api-with-sonnet-5) and
[subscription fallback](docs/operations/DEPLOYMENT.md#subscription-fallback-through-claude--p).
Scheduled review and chat workflows (#24, #25) are not connected.
The status page first answers "is everything OK?" for the database, the archive, the backup, the disk space and AI,
in German or English words from stable codes; a problem is named with its reason. Integrations that are not set up
(AI without credentials; calendar, mail, push and Telegram have no adapter yet) are not listed. After a start, provider
fallback or API-key rotation a configured AI reads "Noch nicht geprüft" until its next run or a manual connection check
([ADR-0018](docs/architecture/adr/ADR-0018-status-overview.md)). Everything else sits in the collapsed "Technische
Details": document processing counts, the backup and disk details, the recurring background jobs, the AI workloads,
budget and runs, the exports and the running version.
The recurring background jobs (review reconciliation, recognized-text check, document reconciliation) show their last
success, next run and last failure, and a job is stale when it has not succeeded for two intervals plus 10 minutes
([ADR-0013](docs/architecture/adr/ADR-0013-recurring-job-health.md)); the overview names a job that does not run as
planned. A watch job
checks them every five minutes and gives each member one in-app notice per incident when a job becomes stale or is
no longer scheduled ([ADR-0014](docs/architecture/adr/ADR-0014-stale-job-notices.md)). The same watch reports the
external backup with a localized reason for a failure, and the free space of the data and backup disks; a failed or
stale backup and a disk with less than 10 % or 5 GB free each notify every member once per incident
([ADR-0015](docs/architecture/adr/ADR-0015-backup-and-storage-health.md)).

The household uses trusted LAN HTTPS at `https://192.168.2.159`. An encrypted external Restic backup
passed isolated recovery and runs daily at 03:30; scheduled-run evidence is recorded in #39.
Aegis can be installed from Chrome on Android and receives PDFs and photos from Android's Share menu into the normal
durable upload flow ([details](docs/operations/DEPLOYMENT.md#installable-app-and-android-share-target)); real-device
verification is tracked in #33. Mail, calendar, MCP, messenger delivery and Web Push are not connected.
For dated verification, use the relevant issues, pull requests and CI runs.

Source is maintained in this repository; the Aegis VM is the deployment environment. Private keys,
tokens, household documents and service data must not be committed.
