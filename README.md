# Aegis

A private household workspace for shared documents and tasks, built with Java/Spring Boot and React.

## Start here

- [Shared engineering instructions](AGENT.md): canonical rules for contributors, Codex, and Claude.
- [Architecture foundations](docs/architecture/FOUNDATIONS.md), [ADR-0001](docs/architecture/adr/ADR-0001-backend-modules-and-transactions.md) (DDD, hexagonal modules, versions, transactions) and [ADR-0002](docs/architecture/adr/ADR-0002-document-tree-and-labels.md) (folder tree, labels, faceted search).
- [GitHub Issues](https://github.com/CedricSchmitter/Aegis/issues): feature requests, open work, acceptance criteria and dependencies.
- [Operational status](docs/operations/STATUS.md): verified capabilities, release evidence and current limitations.
- [Household access](docs/operations/ACCESS.md): accounts and trusted LAN HTTPS.
- [Deployment](docs/operations/DEPLOYMENT.md), [export, restore drill and backup](docs/operations/BACKUP.md), [VM access](docs/VM-ACCESS.md).

AGENTS.md and CLAUDE.md reference AGENT.md to avoid competing instruction sets.

## Repository layout

```text
backend/        Maven multimodule Spring Boot backend (Java 21): shared-kernel, identity, documents, planning,
                notifications, operations, portability, bootstrap, architecture-tests
frontend/       React/TypeScript/Vite web app (German/English), served by the backend from one origin
api/            Committed OpenAPI contract; the TypeScript client is generated from it
e2e/            Playwright browser smoke tests of core household workflows
deploy/         Dockerfile, Compose stack, Caddyfile for the Aegis VM
ops/            Deployment, backup and restore-drill scripts; VM bootstrap
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

Frontend (Node 24):

```bash
cd frontend
npm ci
npm run generate:api          # regenerate src/api/schema.d.ts from ../api/openapi.json
npm run check                 # types, lint, locale catalog and helper tests
npm run build                 # production bundle in frontend/dist
npm run dev                   # dev server with API proxy to http://localhost:8080
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
recovery. Folders, labels and search work without AI; automatic classification is not connected.

The household uses trusted LAN HTTPS at `https://192.168.2.159`. An encrypted external Restic backup
passed isolated recovery and its daily timer is enabled. The last recorded verification has not yet observed
the first scheduled run. Mail, calendar, AI, MCP, messenger delivery, Web Push and Android share-target
integration are not connected. See [operational status](docs/operations/STATUS.md) for dated evidence.

Source is maintained in this repository; the Aegis VM is the deployment environment. Private keys,
tokens, household documents and service data must not be committed.
