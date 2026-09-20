# Aegis

A private household workspace for shared documents and tasks, built with Java/Spring Boot and React.

## Start here

- [Shared engineering instructions](AGENT.md): canonical rules for contributors, Codex, and Claude.
- [Architecture foundations](docs/architecture/FOUNDATIONS.md), [ADR-0001](docs/architecture/adr/ADR-0001-backend-modules-and-transactions.md) (DDD, hexagonal modules, versions, transactions) , [ADR-0002](docs/architecture/adr/ADR-0002-document-tree-and-labels.md) (folder tree, labels, faceted search), [ADR-0003](docs/architecture/adr/ADR-0003-bounded-ai-execution.md) (bounded, replaceable AI execution), [ADR-0004](docs/architecture/adr/ADR-0004-automatic-document-classification.md) (automatic classification, readable filenames) [ADR-0005](docs/architecture/adr/ADR-0005-automatic-folder-placement.md) (automatic folder placement and proposed paths) and [ADR-0006](docs/architecture/adr/ADR-0006-starting-folder-tree.md) (the starting folder tree as a tracked seed).
- [GitHub Issues](https://github.com/CedricSchmitter/Aegis/issues): feature requests, open work, acceptance criteria and dependencies.
- [Household access](docs/operations/ACCESS.md): accounts and trusted LAN HTTPS.
- [Deployment](docs/operations/DEPLOYMENT.md), [export, restore drill and backup](docs/operations/BACKUP.md), [VM access](docs/VM-ACCESS.md).

AGENTS.md and CLAUDE.md reference AGENT.md to avoid competing instruction sets.

## Repository layout

```text
backend/        Maven multimodule Spring Boot backend (Java 21): shared-kernel, identity, documents, contracts,
                planning, notifications, operations, portability, assistant (AI runs), bootstrap,
                architecture-tests
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

CI (`.github/workflows/ci.yml`) runs the backend verification with Testcontainers, the frontend checks with a
generated-client drift check, and a container image build. It needs no private VM access and no live credentials.

## Current state

Aegis supports two individual logins into one shared household, German/English UI, durable PDF capture
with Paperless archival, document list/search/original access, a browsable folder tree with an Unsorted
inbox, structured labels (document type, organization, topic, tags) with faceted search, source-linked
tasks, deterministic Today, in-app notifications, integration status, portable exports and isolated
recovery. Folders, labels and search work without AI. New uploads are classified automatically once archived
(title, type, organization, topics, evidenced issue date; conservative policy with reviewable suggestions), and
downloads and exports use readable filenames such as `2026-09-17_Telekom_Rechnung_Internet_a1b2c3d4.pdf`
([ADR-0004](docs/architecture/adr/ADR-0004-automatic-document-classification.md)). The same run also files the
document into a matching existing folder or proposes a folder path such as `Haus / Versicherungen / Hausrat`;
folders are only created when a member accepts a proposal
([ADR-0005](docs/architecture/adr/ADR-0005-automatic-folder-placement.md)). Existing documents are only
analyzed when a member selects them. A new installation starts with an empty tree; `ops/deploy/folder-tree.json`
and `ops/deploy/seed-folder-tree.py` create the agreed starting tree on request
([ADR-0006](docs/architecture/adr/ADR-0006-starting-folder-tree.md)).

Contract facts are stored as observations with provenance: every value names the document it came from, the
period it is valid for and its review state, a member entering or confirming a value is the only thing that makes
it effective, and an amendment takes over from its own start date while the original stays readable
([ADR-0007](docs/architecture/adr/ADR-0007-contract-facts-and-provenance.md)). Cancellation deadlines are
calculated from confirmed rules only - a stated deadline, or the contract end minus the confirmed notice period -
and are published for other contexts through the contracts application contract. The contracts screen shows each
observation with its provenance, the superseded history of a corrected value, reported conflicts and either the
deadline with the rule it rests on or what is missing to calculate one; automatic extraction (#23/#24) and the
resulting tasks and reminders (#63) are separate issues.

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

The household uses trusted LAN HTTPS at `https://192.168.2.159`. An encrypted external Restic backup
passed isolated recovery and its daily timer is enabled; the first scheduled run succeeded on 2026-09-18.
Aegis can be installed from Chrome on Android and receives PDFs from Android's Share menu into the normal durable
upload flow ([details](docs/operations/DEPLOYMENT.md#installable-app-and-android-share-target)); real-device
verification is tracked in #33. Mail, calendar, MCP, messenger delivery and Web Push are not connected.
For dated verification, use the relevant issues, pull requests and CI runs.

Source is maintained in this repository; the Aegis VM is the deployment environment. Private keys,
tokens, household documents and service data must not be committed.
