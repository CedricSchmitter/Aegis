# Independent release review — 2026-09-13

Scope: implemented first-test release, not a new implementation or production certification. One writer on
`fix/independent-release-review`; only VM 102 `aegis-dev` was accessed. No other VM, Proxmox configuration,
external disk or router was changed.

## Independently observed baseline

- Clean local `main` at `21e272f`; the deployed API, release symlink, container image and `DEPLOYED_COMMIT`
  agreed on `6c9890dd1d1ae632447aa6a759d772b734163634`.
- PR #14 is merged. GitHub CI runs 34758466766 (deployed main) and 34759124327 (handoff main) both completed
  successfully. The latter was still running at review start and subsequently completed.
- Caddy publishes port 80; Aegis, PostgreSQL, Paperless and Valkey publish no host ports. Application,
  database, Paperless and broker containers were healthy. Docker Compose is available through `sudo -n`,
  not directly in the unprivileged user's Docker CLI plugin search path.
- `lsblk` showed only the 100 GiB system disk and optical media. `/srv/aegis` had 81 GiB free. No external
  backup configuration exists. The owner explicitly deferred attaching the nonempty disk until needed.
- `ops/deploy/review-live.py` logged in as both test members without printing credentials. Each saw the
  same three documents and two tasks; every original matched its recorded SHA-256. Anonymous requests to
  documents, tasks, members, status and exports returned 401; logout ended access.
- The existing export manifest `20260913T123418Z` contains two documents, one task and two members with no
  missing originals. Its producer is `3741bf8`, not the currently deployed build. Live counts grew after that
  export; this explains the different counts rather than implying data loss.

Claude's browser observations, six Playwright passes and the earlier real-Paperless local restore drill are
inherited evidence in FIRST-TEST-STATUS.md. Reading a manifest alone does not independently repeat that drill.

## Confirmed findings and corrections

| Area | Evidence and correction |
|---|---|
| Restore data preservation | Import staged under the stable document id, then deleted that path on a duplicate. Repeating a restore could delete a pending/fallback original. Each attempt now owns a separate staging path. Existing ids with different content and checksum collisions under different ids are rejected. Tests cover pending files and archive-outage access after repeat restore. |
| Original integrity | Retrieval trusted archive bytes and retention checked only metadata existence. Retrieval now checks the saved checksum; retention fetches and verifies actual bytes before removing ingress. Corrupt archive bytes retain the good fallback. |
| Interrupted upload | `.part` files remained after a failed stream and were excluded from reconciliation. Failed streams now remove their temporary file; regression checks its absence. |
| Concurrent exports | Second-resolution generation ids and deletion of an existing staging directory allowed requests to interfere. Generations now include UUIDs, staging creation is exclusive, and pointer updates use unique temporary files. |
| Request failures | Live invalid UUID returned HTTP 500. Malformed bodies, invalid dates/ids and missing parts now return localized `validation.failed` HTTP 400 with advice ordered ahead of Spring's default problem handler. |
| Task ownership | Create/assign accepted arbitrary UUID owners without membership checks. Both commands now reject unknown/inactive owners. Existing deadline/planned-date separation and version checks remain intact. |
| Backup preparation | Previous script dumped while writers ran, checked only a textual destination prefix, and retained a success state after later command failures. The replacement verifies canonical paths and a mounted destination, requires explicit Restic initialization, stops application writers during both dumps and file capture, restarts them on failure, preserves last success while reporting failure, and reports success older than 26 hours as degraded. No automatic pruning or initialization. |
| Deployment evidence | Deployment wrote its success marker before building. It now waits for health and verifies the running version before updating the marker; optional HTTPS mode survives later deployments. |

## Accepted household preparation

- Owner approved `anna -> lisa / Lisa`, `ben -> cedric / Cedric`. The explicit operator migration preserves
  member IDs, password hashes, ownership, timestamps and sessions. It also updates the provisioning seed so
  a restart cannot recreate test accounts. An isolated PostgreSQL test covers idempotency and old/new logins.
- Preferred hostname: `aegis`. Prepared Caddy local-CA HTTPS and secure session cookies. The name did not
  resolve on the owner's PC during review. DNS and certificate trust on both Android phones and the PC must
  be established before switching the main endpoint. See HOUSEHOLD-PILOT.md.
- No external disk work now. An actual external backup and a restore from it are gates before real household
  documents. Mail, calendar writes, AI and push remain deferred.
- Public/internet access is explicitly deferred until local use is proven. The owner clarified this after the
  local-DNS question; no public exposure or router changes were made. Private-IP HTTPS remains an alternative
  to configuring a local name.

## Verification and practical limits

Regression tests run on a separate source copy and disposable PostgreSQL containers inside the Aegis VM;
the first pass exposed missing error-code precedence, which was corrected. Backup tests use mocked commands
and temporary directories, not an actual disk. Frontend type/lint/locale tests and production build passed.
Caddy 2.11.4 accepted the prepared HTTPS configuration in a disposable container.

The final VM Maven run completed successfully at 2026-09-13 15:33 CEST: 64 unit tests, 8 architecture tests
and 16 PostgreSQL integration tests (88 total, no failures/skips). Four Python backup tests and six frontend
tests passed. The local Windows Maven wrapper did not start; the full Maven verification was run on the VM.
PR [#15](https://github.com/CedricSchmitter/Aegis/pull/15) merged fast-forward at 15:41 UTC after
[CI 34765833558](https://github.com/CedricSchmitter/Aegis/actions/runs/34765833558) passed all four jobs,
including the real-Paperless browser smoke and application restart/persistence check.
The subsequent [main CI 34766346793](https://github.com/CedricSchmitter/Aegis/actions/runs/34766346793)
also completed successfully in all four jobs for the exact deployed commit `e148ef8`.
No Android device test, externally stored Restic restore, or browser certificate-trust test is claimed.
Portable exports are one-way views, not coordinated full-system backups. The offline backup procedure still
needs its first real-destination recovery exercise and scheduling before it can protect household data.

## Household operations independently verified

- The owner-approved account migration ran on the live VM from the tracked operator script. Before/after
  checks confirmed identical member IDs, password hashes, preferences, document records and task records.
  A session opened as anna before the migration continued to return the same member ID as lisa afterward.
  Both `lisa` and `cedric` subsequently logged in and retrieved all three checksum-verified originals.
- A fresh export `20260913T153456Z` contains the three documents, two tasks and new household names.
- The isolated restore drill using image `e148ef8` passed for that export: all three originals were re-archived
  and checksum-verified; both tasks retained their state, deadline and source link. The disposable stack was
  removed afterwards. This is independent real-Paperless restore evidence, not an external backup.
- Caddy's persistent local CA was prepared on the VM. A temporary proxy bound only to `127.0.0.1:18443`
  served the live version endpoint with successful TLS/root verification for `aegis`, then was removed.
  Only the public root certificate was copied to the owner's PC; client trust was not installed and the
  household HTTP endpoint was not switched. The root fingerprint is recorded in HOUSEHOLD-PILOT.md.

## Deployed review release

Commit `e148ef8af157f28ba4cd6f649db5e3da2e841e90` was deployed after the successful PR CI. The deployment
waited for health and checked the running version before updating its marker. `/api/system/version` and the
release symlink agree. Post-deployment live checks verified both new logins, identical collections, all three
original checksums, two tasks, anonymous rejection, logout, and HTTP 400 for malformed document ids.

Export `20260913T154219Z-7cda357a-ddb4-47dc-941c-005832f3af17` is the first independently verified generation
from the deployed review build, with no missing originals. The following commit may contain documentation
evidence only; deployment remains tied to the tested application commit above.
The final documentation-only evidence commit skips redundant CI; no application or operations code changes
follow the verified release. Final VM inventory shows only the five intended containers, healthy application,
Paperless, PostgreSQL and broker, and approximately 80 GiB free on the system filesystem.

Follow-ups: [#16 trusted household HTTPS](https://github.com/CedricSchmitter/Aegis/issues/16) and
[#17 external destination and recovery](https://github.com/CedricSchmitter/Aegis/issues/17).
