# First test execution status

Updated: 2026-09-13 (milestone verified; PR #14 merged fast-forward into `main`, the deployed commit is the
`main` head reported by `/api/system/version`). Durable handoff for the autonomous
implementation of milestone "First runnable test" (#1). Never record passwords, tokens or private keys here.

## Current state

- Branch `feature/backend-foundation`, pull request #14 against `main`. Latest commits fix deployment details
  found on the VM (archive line endings, container gid, Paperless multipart upload, runner ordering).
- Deployed on VM 102 `aegis-dev`: `http://192.168.2.159/` (LAN-only HTTP preview, Caddy on port 80; PostgreSQL,
  Valkey and Paperless are not published). The running commit is shown at `/api/system/version` and in the UI
  footer; `/srv/aegis/deploy/DEPLOYED_COMMIT` records it on the VM.
- Initial logins: `/srv/aegis/deploy/secrets/initial-members.json` on the VM (mode 0640, group `aegis-app`).
  Members `anna` and `ben` were provisioned at first start.
- Host preparation done on the VM: `aegis-agent` in groups `docker` and `aegis-app` (gid 10001, matches the
  application container user), data directories under `/srv/aegis/data`, exports under `/srv/aegis/exports`.

## Verified on the deployed stack (real Paperless-ngx 3.1.3)

- Browser (owner's PC, Claude browser pane): German login page with preview banner and version footer; login as
  `anna`; Today with "no calendar connected"; documents page; upload; document detail shows honest states
  (first attempt FAILED with the Paperless validation message, later retried from the UI and ARCHIVED with
  Paperless no. 1); status page (database CONNECTED, Paperless CONNECTED 3.1.3, export DEGRADED before the first
  export, external backup / calendar / mail / AI / Web Push / Telegram NOT_CONFIGURED); mobile viewport with
  bottom navigation.
- API over the LAN URL as `ben`: original download returns the exact fixture bytes (SHA-256
  e06c1e53ce533169c1c5f86a184c75c52e19f4818866623ba61438159721fc1d) with `X-Aegis-Original-Source: ARCHIVE`;
  full-text search for "Kuendigungsfrist" (text inside the PDF) returns the document with `contentSearch: READY`.
- Playwright smoke (`e2e/`) against `http://192.168.2.159`: 6 of 6 passed, including unique-PDF upload,
  archival wait, checksum, content search, linked task with deadline kept while rescheduling work, notice for the
  other member, completion visible to both, status states, mobile layout.
- Restart of `aegis` and `caddy` containers only: readiness 200 afterwards, documents and tasks still present
  (Playwright "records persist after restart" passed), sessions survive (stored in PostgreSQL).
- Export created via `POST /api/exports`: generation 20260913T123418Z with 2 documents, 1 task, 2 members,
  no missing originals, published under `/srv/aegis/exports/generations/`.
- Restore drill (`ops/backup/restore-drill.sh` at 409572f): PASSED for generation 20260913T123418Z. The
  disposable stack `aegis-drill` (own PostgreSQL, Valkey, Paperless, bound to 127.0.0.1:18080) imported both
  documents under their stable ids, re-archived them in the isolated Paperless (new external ids), served originals
  matching the exported checksums and restored the task with its source link and state; the stack was removed
  afterwards. First two attempts had failed on host permissions (exports readable only by uid 10001) and on the
  restore runner starting before member provisioning; both are fixed (group `aegis-app`, `@Order` on the
  provisioner).

## Tests/checks actually run

- VM: `./mvnw -Daegis.openapi.export=true verify` at 58e9fbe: BUILD SUCCESS (unit, 8 architecture tests, 14
  PostgreSQL integration tests).
- CI (PR #14) at 409572f: all four jobs green, including the compose-based browser smoke with a real
  Paperless container: https://github.com/CedricSchmitter/Aegis/actions/runs/34758102047. An earlier smoke run
  had failed on a login-return-target race (fixed in 11618ae: logout leaves the route first, the e2e login helper
  no longer assumes `/today`).
- Local: `npm run check` and `npm run build` green.

## Milestone closure

PR #14 was merged fast-forward so the deployed commit is on `main`; issues #2–#13 and #1 are closed with
evidence comments. Follow-up milestones (HTTPS, external backup destination, mail/calendar/AI, phone push, the
two-week pilot) remain open as separate work.

## Known limitations of this test

- LAN-only HTTP: no TLS, no installable PWA, no Web Push. HTTPS needs an owner decision (hostname/certificate).
- External backup is prepared but not configured; the local drill is not a backup.
- Mail, calendar, AI, Telegram are explicitly not connected.
- Sessions time out after 30 days; passwords can be changed under Settings.
