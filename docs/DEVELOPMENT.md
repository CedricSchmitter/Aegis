# Development

How the repository is laid out and how to build, test and verify it. The engineering rules live in [AGENT.md](../AGENT.md).

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

The household assistant's reference questions (`fixtures/assistant-eval`, issue #25) run as a live evaluation that
is skipped unless enabled. Each question costs about 0.04 USD API-equivalent per retrieval and repetition; without a
key file it uses the owner's subscription through the host runner socket, which needs the owner's consent. The raw
results go to `backend/assistant/adapters/target/assistant-eval/` and are summarized by the ops script below:

```bash
AEGIS_CHAT_EVAL=true AEGIS_ANTHROPIC_API_KEY_FILE=/path/to/anthropic-api-key AEGIS_CHAT_EVAL_REPETITIONS=1 \
  ./mvnw -pl assistant/adapters -am -DskipITs test -Dtest=ChatReferenceLiveTest -Dsurefire.failIfNoSpecifiedTests=false
python3 ../ops/eval/chat_reference_check.py report assistant/adapters/target/assistant-eval/*.json
```

The same questions run against the deployed instance with `ops/eval/chat_reference_check.py`; see
[Deployment](operations/DEPLOYMENT.md#household-assistant-reference-questions). Its scoring has offline tests:

```bash
python3 -m unittest discover -s ops/eval -p 'test_*.py'
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
AEGIS_BASE_URL=https://<aegis-host> AEGIS_USER_A=lisa AEGIS_PASSWORD_A=... AEGIS_USER_B=cedric AEGIS_PASSWORD_B=... npm test
```

CI runs on pull requests only, and each workflow only when its paths change: `backend.yml` (Maven verify with
Testcontainers), `frontend.yml` (checks, build and generated-client drift check), `ops.yml` (Python tests of the
ops scripts) and `smoke.yml` (image build and browser smoke against the compose stack, automatically for `deploy/`
and `e2e/` changes, otherwise started by hand with `gh workflow run smoke.yml --ref <branch>`). Every workflow can
also be started by hand. CI needs no private VM access and no live credentials.

In a Claude Code cloud session (claude.ai/code) the backend, frontend and ops checks above run as written; the browser
smoke test and deployment need the LAN and therefore the PC. The cloud container has Docker installed but not
running, so the integration tests need it started first. The cloud environment's setup script does that for every
session:

```bash
#!/bin/bash
# Aegis: start Docker so the backend integration tests (Testcontainers) can run.
if ! docker info >/dev/null 2>&1; then
  nohup dockerd >/tmp/dockerd.log 2>&1 &
  for i in $(seq 1 30); do docker info >/dev/null 2>&1 && break; sleep 1; done
fi
```

Without it, `sh ops/cloud/session.sh start` starts Docker in the session. `sh ops/cloud/session.sh stop` removes all
containers, images, volumes and build cache and stops Docker; the `SessionEnd` hook in `.claude/settings.json` runs it
when a session ends, and the container itself is discarded afterwards.
Outside a cloud session both commands do nothing.

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
