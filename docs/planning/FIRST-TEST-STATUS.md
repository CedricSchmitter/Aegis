# First test execution status

Updated: 2026-09-13 (deployment in progress). Durable handoff for the autonomous implementation of milestone
"First runnable test" (#1). Never record passwords, tokens or private keys here.

## Current state

- Branch `feature/backend-foundation`, commit 58e9fbe, pushed; pull request #14 open against `main`.
- Server: VM 102 `aegis-dev` 192.168.2.159. `aegis-agent` is in the `docker` group (needed for Testcontainers).
  Secrets and data directories were generated with `ops/deploy/init-secrets.sh`; the initial logins are in
  `/srv/aegis/deploy/secrets/initial-members.json` (mode 0600, VM only).
- Deployment of commit 58e9fbe via `ops/deploy/deploy.sh` is running (first image build and Paperless pull).
- Local PC: Java 21 and Node 24, no Docker, no Maven (wrapper used). PostgreSQL integration tests run on the VM
  and in CI only.

## Implemented and verified so far

| Issue | State | Evidence |
|---|---|---|
| #2 modules and architecture checks | done | 19 Maven modules; ArchUnit rules plus violation fixtures green (VM `./mvnw verify`) |
| #3 PostgreSQL, migrations, jobs | done | Flyway V0–V4; `AtomicSchedulingIT` (rollback, commit, resume, idempotent scheduling) green on the VM |
| #4 two-user shared auth | done | `ApiFlowIT`: 401/403 problem details, wrong password, CSRF, both logins, shared visibility |
| #5 German/English UI | done, browser check pending | catalogs parity test, generated client from `api/openapi.json`, `npm run check` and `build` green |
| #6 Compose deployment | in progress | files in `deploy/`, `ops/deploy/*.sh`; first deployment running |
| #7 durable upload + Paperless | code done, live check pending | `ApiFlowIT` upload/archive/duplicate/outage-retry with in-process archive fake; `PaperlessClientTest` for the HTTP mapping |
| #8 list/search/original | done, browser check pending | `ApiFlowIT` search states and original with source header |
| #9 tasks and Today | done | domain tests, `TaskCommandServiceTest`, `ApiFlowIT` conflict 409 and completion |
| #10 notices and status | done | `NoticeServiceTest`, `ApiFlowIT` notices and status states |
| #11 export/restore/backup prep | code done, VM drill pending | `RestoreIT` (wipe and restore with new external ids), `ops/backup/*` |
| #12 CI and smoke | workflow pushed, first run pending | `.github/workflows/ci.yml` incl. compose smoke; `e2e/` Playwright |
| #13 deploy, verify, report | in progress | |

## Tests/checks actually run

- VM (commit 58e9fbe): `./mvnw -Daegis.openapi.export=true verify` BUILD SUCCESS: all unit tests, 8 architecture
  tests, 14 integration tests (ApiFlowIT 8, AtomicSchedulingIT 4, RestoreIT 1, OpenApiSnapshotIT 1).
- Local: `npm run check` (tsc, eslint, 6 vitest tests) and `npm run build` green.

## Open failures and attempted remedies

- Resolved on the way: failsafe not bound / ran against repackaged jar; `CHAR(64)` vs `VARCHAR` schema validation;
  raw service beans not injectable (`autowireCandidate=false`) replaced by holder records plus per-interface
  proxies; scheduler bean cycle broken with lazy `ObjectProvider`; Testcontainers container stopped between IT
  classes, now a JVM singleton; `Actor` appeared as an OpenAPI query parameter, now ignored by springdoc.

## Next concrete action

Wait for the deployment, verify `/api/system/version` and readiness, run the browser flow at
`http://192.168.2.159/`, run the restore drill and the Playwright smoke against the VM, watch CI on PR #14, merge,
redeploy the `main` commit, close issues with evidence and report to the owner.
