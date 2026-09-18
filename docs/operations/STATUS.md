# Verified operational state

Last recorded verification: 2026-09-18. This is dated evidence, not a live health check.
Use the application Status page and the deployment markers to inspect the current installation.
Future work is tracked in [GitHub Issues](https://github.com/CedricSchmitter/Aegis/issues).

## Application

- VM 102 serves `https://192.168.2.159` on the household LAN. On 2026-09-18 09:29 the VM was brought from
  `364b322` to `96e177c` (main with the review corrections of `5759743` from PR #51) and at 12:41 to `85404a9`
  (PR #54, bounded AI execution); each time marker, running version and HTTPS answer matched afterwards.
  HTTPS configuration commit `30554ee` is recorded separately. Check
  `/api/system/version`, `/srv/aegis/deploy/DEPLOYED_COMMIT` and `/srv/aegis/deploy/HTTPS_CONFIG_COMMIT` before
  operating on it.
- Both members share documents and tasks through individual accounts. The migration from test identities
  to `lisa` and `cedric` preserved IDs, ownership and sessions. The owner confirmed individual passwords
  and successful fresh logins; those later credential changes were not independently agent-verified.
- Implemented capabilities: German/English UI, durable PDF capture, Paperless archival and full-text search,
  original retrieval, a browsable folder tree with an Unsorted inbox, structured labels (document type,
  organization, topic, tags) with folder-implied labels and faceted search, source-linked tasks, deterministic
  Today, in-app notifications, integration status, portable exports and isolated recovery. Folders, labels and
  search work without AI; automatic classification is not connected.
- Bounded AI execution ([ADR-0003](../architecture/adr/ADR-0003-bounded-ai-execution.md), issue #21): durable runs
  with per-workload provider, model and budgets, visible state, cancellation, retry, audit steps, a connection
  check and a Claude API adapter. No provider key is configured, so AI is reported as not configured and no model
  has been called from the VM; classification, review and chat workflows (#23, #24, #25) are not connected.
- Trusted TLS, redirects, session-cookie settings, authorization and original checksums were independently
  checked. The owner confirmed upload, original access, task assignment, notification and completion on
  both Android phones. Real household data is present and must be preserved.
- Mail, calendar, MCP, messenger delivery, Web Push and Android share-target integration are not connected.
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
- [PR #51](https://github.com/CedricSchmitter/Aegis/pull/51) added the document folder tree, the structured
  label vocabulary and faceted search for issues #20 and #46, with the ownership, cardinality and search
  decisions in [ADR-0002](../architecture/adr/ADR-0002-document-tree-and-labels.md). Verified on 2026-09-18:
  [CI run 35283222691](https://github.com/CedricSchmitter/Aegis/actions/runs/35283222691) passed all four jobs
  for commit `b5d452d` with 127 unit tests, 8 architecture tests, 30 PostgreSQL integration tests, 24 frontend
  tests, the container image build and 12 browser tests against real Paperless. The search integration test
  measured folder and facet queries over 10,000 seeded documents at 131 ms (listing), 493 ms (with facet
  counts), 189 ms (folder plus text) and 124 ms (missing-value filter).
- Backward compatibility of the changed export format was checked on the VM: the isolated restore drill
  (`ops/backup/restore-drill.sh`) restored the existing schema 1 generation `20260913T154219Z` into the new code
  and passed with three re-archived documents under new Paperless ids and two tasks with intact source links.
- The same commit is deployed on VM 102 and was exercised through `https://192.168.2.159` in German and English,
  on the desktop layout and at phone width, with two temporary QA accounts. The QA accounts, the test documents,
  folders and labels were removed afterwards; the household documents, tasks and members are unchanged, and the
  folder tree and the custom vocabulary start empty.
- The owner confirmed on 2026-09-18 that creating folders, filing documents, setting labels and filtering work
  on both Android phones; the agent did not operate the phones itself. Issue #20 is closed. Automatic
  classification (#23), bounded AI execution (#21), assistant retrieval (#25) and the optional backfill of
  existing documents remain open parts of #46. The browser suite asserts the CI state of the external backup, so a LAN run against this VM
  reports that one integration expectation as failed while the backup is configured here.
- [PR #54](https://github.com/CedricSchmitter/Aegis/pull/54) added the bounded AI execution boundary for #21
  ([ADR-0003](../architecture/adr/ADR-0003-bounded-ai-execution.md)).
  [CI run 35334952424](https://github.com/CedricSchmitter/Aegis/actions/runs/35334952424) passed all four jobs for
  `b6d4b09`: 217 unit tests (the separately enabled Claude API live test skipped), 12 architecture tests (including
  the rules that confine the Anthropic SDK to its adapter and forbid process execution), 42 PostgreSQL integration
  tests, 24 frontend tests, the image build and 12 browser tests against real Paperless. The first CI run failed in
  the catalog browser test because it navigated before the language preference was stored; the tests now wait for
  that request. The controlled-adapter tests cover malformed, truncated and refused answers, timeouts, rate limits,
  rejected credentials, cost, daily, time, model-call and tool-call budgets, restart and revived jobs, cancellation
  during a model call, concurrent cancellation, stale sources, repeated effects, prompt injection with ungranted
  tools, membership loss and a model outage while capture, search and tasks keep working.
- Deployment of `85404a9`: a `pg_dump` of the Aegis database was taken first
  (`/srv/aegis/backup-staging/aegis-before-85404a9.dump`, root-only); migration 5.1 (`ai_runs`, `ai_run_steps`,
  `ai_run_effects`) applied; documents, tasks, members, folders and labels were unchanged (5, 3, 2, 1, 35); the AI
  endpoints answer only authenticated requests and the empty key directory is mounted read-only.
- The deployed image was exercised in an isolated QA instance on the VM (throwaway PostgreSQL, two QA-only
  accounts, loopback-only ports, a controlled stand-in for the Messages API; no household data or container
  involved; removed afterwards). Through the real SDK adapter the connection check succeeded for
  `claude-haiku-4-5` (1 call, 0.0009 USD recorded) and reported a simulated unavailable `claude-opus-5` as
  `MODEL_UNAVAILABLE`, in German at desktop width and in English at phone width without horizontal overflow. It also
  showed that the integration status judged only the latest finished run and reported "connected" despite the
  unavailable model; PR #55 judges each configured model instead.

## Recovery

The encrypted external Restic snapshot passed a full read check and isolated recovery of both databases,
originals, members and task links. A second snapshot succeeded through the systemd service. Daily backup
is enabled at 03:30 Europe/Berlin; the first scheduled run started at 03:30:03 on 2026-09-18 and completed
successfully at 03:31:30 (systemd journal of `aegis-backup.service`).
Failure or a last success older than 26 hours produces a degraded backup status.
See [BACKUP.md](BACKUP.md) for destination identity, snapshot evidence and recovery commands.

Operational procedures: [deployment](DEPLOYMENT.md), [household access](ACCESS.md),
[VM access](../VM-ACCESS.md). Historical implementation reports remain in Git history and completed issues.
