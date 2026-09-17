# Verified operational state

Last recorded verification: 2026-09-17. This is dated evidence, not a live health check.
Use the application Status page and the deployment markers to inspect the current installation.
Future work is tracked in [GitHub Issues](https://github.com/CedricSchmitter/Aegis/issues).

## Application

- VM 102 serves `https://192.168.2.159` on the household LAN. The last verified application release is
  `e65a1b6`; HTTPS configuration commit `30554ee` is recorded separately. Check `/api/system/version`,
  `/srv/aegis/deploy/DEPLOYED_COMMIT` and `/srv/aegis/deploy/HTTPS_CONFIG_COMMIT` before operating on it.
- Both members share documents and tasks through individual accounts. The migration from test identities
  to `lisa` and `cedric` preserved IDs, ownership and sessions. The owner confirmed individual passwords
  and successful fresh logins; those later credential changes were not independently agent-verified.
- Implemented capabilities: German/English UI, durable PDF capture, Paperless archival and full-text search,
  original retrieval, source-linked tasks, deterministic Today, in-app notifications, integration status,
  portable exports and isolated recovery.
- Trusted TLS, redirects, session-cookie settings, authorization and original checksums were independently
  checked. The owner confirmed upload, original access, task assignment, notification and completion on
  both Android phones. Real household data is present and must be preserved.
- Mail, calendar, AI, MCP, messenger delivery, Web Push and Android share-target integration are not connected.
  Public access and local DNS are not configured.

## Verification evidence

- The independent release review fixed restore idempotency, archive checksum verification, interrupted-upload
  cleanup, concurrent exports, invalid-input responses and owner membership validation. Its final Maven run
  passed 64 unit tests, 8 architecture tests and 16 PostgreSQL integration tests. Four backup-script tests and
  six frontend tests also passed. These are historical results, not tests repeated during documentation cleanup.
- [PR #15](https://github.com/CedricSchmitter/Aegis/pull/15) and
  [CI run 34765833558](https://github.com/CedricSchmitter/Aegis/actions/runs/34765833558) record the review release;
  all four jobs passed, including real-Paperless browser smoke and restart/persistence coverage.
- [PR #18](https://github.com/CedricSchmitter/Aegis/pull/18) corrected HTTPS host-preserving redirects and
  certificate selection without SNI; [CI run 35216385600](https://github.com/CedricSchmitter/Aegis/actions/runs/35216385600)
  passed all four jobs.
- [PR #19](https://github.com/CedricSchmitter/Aegis/pull/19) records external recovery and backup-status work;
  [CI run 35237838666](https://github.com/CedricSchmitter/Aegis/actions/runs/35237838666) passed all four jobs.
  Its merge commit `e65a1b6` was verified on the VM and through trusted HTTPS.

## Recovery

The encrypted external Restic snapshot passed a full read check and isolated recovery of both databases,
originals, members and task links. A second snapshot succeeded through the systemd service. Daily backup
is enabled at 03:30 Europe/Berlin; the first scheduled run had not yet been observed at this verification date.
Failure or a last success older than 26 hours produces a degraded backup status.
See [BACKUP.md](BACKUP.md) for destination identity, snapshot evidence and recovery commands.

Operational procedures: [deployment](DEPLOYMENT.md), [household access](ACCESS.md),
[VM access](../VM-ACCESS.md). Historical implementation reports remain in Git history and completed issues.
