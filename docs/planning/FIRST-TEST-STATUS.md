# First test execution status

Updated: 2026-09-13 (in progress). Durable handoff for the autonomous implementation of milestone
"First runnable test" (#1). Never record passwords, tokens or private keys here.

## Current state

- Branch: `feature/backend-foundation` (from `main` at bd5a32e). Implementation of the complete slice is
  committed; integration verification on the VM is in progress.
- Server: VM 102 `aegis-dev` 192.168.2.159; `aegis-agent` was added to the `docker` group so Testcontainers can
  run there. No Aegis stack deployed yet; `/srv/aegis/source/worktree` holds a synced working copy for test runs.
- Local PC: Java 21 and Node 24 available, no Docker, no Maven (wrapper used). PostgreSQL integration tests run
  on the VM and in CI only.

## Implemented (commit fff950c and later)

| Issue | State | Evidence |
|---|---|---|
| #2 modules and architecture checks | implemented | 19 Maven modules, ArchUnit rules plus violation fixtures pass locally and on the VM |
| #3 PostgreSQL, migrations, jobs | implemented | Flyway V0–V4, db-scheduler with transaction-aware data source, `AtomicSchedulingIT` (VM run pending) |
| #4 two-user shared auth | implemented | session login, BCrypt, CSRF, `HouseholdAccess` in every use case, `ApiFlowIT` |
| #5 German/English UI | implemented, build pending | React/Vite app, catalogs with parity test, generated client pending OpenAPI snapshot |
| #6 Compose deployment | files ready, not deployed | `deploy/`, `ops/deploy/*.sh` |
| #7 durable upload + Paperless | implemented | `DocumentCaptureService`, `ArchiveDocumentTask`, `PaperlessClient` + tests; live Paperless check pending |
| #8 list/search/original | implemented | `DocumentQueryService`, REST, UI pages |
| #9 tasks and Today | implemented | `Task`, `TodayPolicy`, `TaskCommandService` tests, UI |
| #10 notices and status | implemented | `NoticeService`, status sources, UI |
| #11 export/restore/backup prep | implemented | `ExportService`, `RestoreService`, `RestoreIT`, `ops/backup/*` |
| #12 CI and smoke | files ready | `.github/workflows/ci.yml`, `e2e/` Playwright; first run pending |
| #13 deploy, verify, report | open | |

## Tests/checks actually run

- Local: `./mvnw -DskipITs verify` green (all unit and architecture tests) at commit fff950c.
- VM: architecture tests green; bootstrap integration tests currently being fixed (schema validation of
  `checksum_sha256` CHAR vs VARCHAR corrected; rerun in progress).

## Open failures and attempted remedies

- Failsafe initially skipped the ITs (plugin not bound) and then ran against the repackaged jar; fixed by
  binding the plugin in the parent POM and pointing `classesDirectory` at `target/classes`.

## Next concrete action

Get the bootstrap ITs green on the VM, copy `api/openapi.json` back, generate the frontend client, build the
frontend, push the branch, open the PR, then deploy with `ops/deploy/deploy.sh` and verify in the browser.
