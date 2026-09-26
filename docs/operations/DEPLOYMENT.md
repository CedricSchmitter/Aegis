# Deployment on the Aegis VM

Target: Proxmox VM 102 `aegis-dev`, `192.168.2.159`, user `aegis-agent` (see [VM-ACCESS.md](../VM-ACCESS.md)).
Application deployment touches only this VM. The separately authorized backup setup passed the identified
`BackupIntenso` USB device through Proxmox to VM 102; Home Assistant, Mealie and the other host disk were
untouched.

## What runs

`deploy/compose.yaml` starts five containers in the Compose project `aegis`:

| Service | Image | Published port | Persistent data |
|---|---|---|---|
| caddy | caddy:2.11.4-alpine | 80 and 443 (LAN, HTTPS override) | /srv/aegis/data/caddy |
| aegis | built from `deploy/Dockerfile` as `aegis-app:<commit>` | none | /srv/aegis/data/ingress, /srv/aegis/exports |
| postgres | postgres:17.11 | none | /srv/aegis/data/postgres (databases `aegis` and `paperless`) |
| broker | valkey/valkey:9-alpine | none | /srv/aegis/data/valkey |
| paperless | ghcr.io/paperless-ngx/paperless-ngx:3.1.3 | none | /srv/aegis/data/paperless |

Only Caddy is reachable from the LAN. PostgreSQL, Valkey, Paperless and Docker administration are not
published. Aegis talks to Paperless over the internal Compose network with a service account.

## Access mode: trusted LAN HTTPS

The first synthetic test used HTTP. The current endpoint is `https://192.168.2.159/` with the local CA trusted
on the PC and both Android phones. `AEGIS_HTTPS=true` enables the Compose override and Secure session cookies.
The preferred local name `aegis` remains optional and unconfigured. See [ACCESS.md](ACCESS.md)
for verification and owner-confirmed phone tests. No Web Push or public access is configured. External
backup and isolated recovery passed; one real invoice is already stored and must be preserved.

## First-time setup

```bash
# on the VM, as aegis-agent
sh /srv/aegis/source/current/ops/deploy/init-secrets.sh
```

`init-secrets.sh` writes `/srv/aegis/deploy/.env` (database and Paperless secrets) and
`/srv/aegis/deploy/secrets/initial-members.json` (the two household logins with random initial passwords),
and prepares the data directories. The environment file stays mode 0600; the members file is written
0640 with group 10001 so the application container can read it. It never overwrites existing files.

## Deploy an identified commit

From the developer PC (uses the verified SSH identity; no token or private key is copied to the VM):

```bash
sh ops/deploy/deploy.sh <commit-ish>
```

The script ships `git archive` of the commit to `/srv/aegis/source/releases/aegis-<sha>`, links
`/srv/aegis/source/current`, then runs `ops/deploy/remote-deploy.sh` on the VM: copy compose files into
`/srv/aegis/deploy`, prepare the optional AI key directory, pin `AEGIS_COMMIT=<sha>` in `.env`, build
`aegis-app:<sha>` and `docker compose up -d`.
The script updates `DEPLOYED_COMMIT` only after readiness and running-version checks pass. The running
commit is visible at `/api/system/version`, in the desktop status bar and under "Technische Details" on the status page.

Before deploying a commit that migrates household data, keep a restorable dump of the household database next to
the backup staging area (on the VM, as `aegis-agent`; the file stays on the VM):

```sh
sudo -n sh -c 'cd /srv/aegis/deploy && docker compose --env-file .env -f compose.yaml exec -T postgres \
    pg_dump -U aegis -d aegis -Fc' > /srv/aegis/backup-staging/aegis-before-<sha>.dump
```

Rollback to a previously built commit: `sh /srv/aegis/source/current/ops/deploy/rollback.sh <sha>` on the VM
(application container only; database migrations are additive and are not undone). A successful deployment records
the commit it replaced in `PREVIOUS_COMMIT` and then runs the bounded cleanup described in [Disk space](#disk-space):
only the current image and the one in `PREVIOUS_COMMIT` are guaranteed to exist; older `aegis-app` images are removed.

## Onboarding the two logins

The current login is at `https://192.168.2.159/login`. Passwords are stored as
BCrypt hashes. The explicit household onboarding maps the test accounts to `lisa` and `cedric` without
resetting passwords; see ACCESS.md. The provisioning seed passwords are stale and must not be used
for login checks. The owner confirmed both accounts changed from shared temporary test passwords to
individual passwords and both new logins worked; the agent did not independently verify the newer credentials.
Never print credentials in logs or Git.
The JSON file only seeds accounts that do not exist yet. After onboarding/password changes, retire its
plaintext values by replacing its content with `{"members": []}`; keep the bind-mounted file in place so
later container recreation does not encounter a missing file or create a directory in its place.

## Assistant account

The household's assistant account ([ADR-0017](../architecture/adr/ADR-0017-persons-and-the-assistant-account.md)) is a member that logs in, reads the whole
household, receives every notice and can be responsible for a task, but is never what a document is about: it is not
offered as a subject, not shown in the subject filter and not among the persons the classifier receives. On this
installation it is the agent login `aegis-dev`, shown as `Aegis`. Which account it is, is environment data in
`/srv/aegis/deploy/.env`, set with a tracked, idempotent script:

```sh
# on the VM, as aegis-agent, from a checkout of the commit (or from the PC before deploying it:
#   ssh <verified options> aegis-agent@192.168.2.159 'python3 - aegis-dev' < ops/deploy/set-assistant-member.py)
python3 ops/deploy/set-assistant-member.py aegis-dev
```

The script sets `AEGIS_ASSISTANT_MEMBERS=aegis-dev`, keeps every other line and the file mode, prints nothing else
from the file and changes nothing when the value is already set. At the next start of the application container
(`deploy.sh`, or `docker compose ... up -d aegis`) the account is designated once and renamed to `Aegis`
(`aegis.members.assistant-display-name`); the username `aegis-dev` and its password stay. The designation is stored
in identity, so removing the variable later does not turn the account back into a person, and a later rename is
kept. The log shows `Assistant designation for 'aegis-dev': DESIGNATED` once and `ALREADY_ASSISTANT` afterwards.

**Order for the version that retires the subject "Beide".** Set the variable **before** that version starts for the
first time. The same start replaces every stored "Beide" by one subject per person (log line
`Replaced the subject 'both' by 2 persons on … documents, … folders and … analyses`); if `aegis-dev` were still a
person at that moment, it would be added to those documents too and would have to be removed by hand on each
document page. Later starts find nothing to replace and log nothing.

## Starting folder tree (optional, on request)

A new installation starts with an empty folder tree and the Unsorted inbox. The tree agreed for this household is
tracked as data in `ops/deploy/folder-tree.json`; `ops/deploy/seed-folder-tree.py` creates it through the ordinary
REST API. It is not part of deployment, and the application never creates folders by itself
([ADR-0006](../architecture/adr/ADR-0006-starting-folder-tree.md)).

Read the data file first and edit the names and implied labels if the household's life areas differ; every implied
label on a folder's path gates automatic filing
([ADR-0002 naming rules](../architecture/adr/ADR-0002-document-tree-and-labels.md#folder-naming-rules)). Then run a
plan and only afterwards apply it, from the VM against the trusted HTTPS endpoint:

```sh
# on the VM, as aegis-agent, from a checkout of the deployed commit
sudo -n cat /srv/aegis/data/caddy/data/caddy/pki/authorities/local/root.crt > /tmp/aegis-root.crt
python3 ops/deploy/seed-folder-tree.py --base-url https://192.168.2.159 --ca-cert /tmp/aegis-root.crt     --credentials /srv/aegis/deploy/secrets/aegis-dev-login.json
# repeat with --apply once the plan is what you want
```

The script is idempotent: folders are matched by normalized sibling name under the same parent, so a second run
reports no changes and never creates a near-duplicate of an existing folder. It writes nothing without `--apply`,
and it deletes nothing without `--prune`, which removes only folders the data file does not list and refuses any
folder that holds documents or still has a kept subfolder. `--prune` reads the data file as the complete tree, so
a partial file would propose deleting everything else; read the printed plan before adding `--apply`. A built-in label key that no longer resolves fails the
run before anything is written. Credentials come from the JSON file or from `--username` with `AEGIS_PASSWORD`;
they are never printed. Delete the copied CA certificate afterwards.

## AI provider (optional)

AI runs through the bounded execution boundary of [ADR-0003](../architecture/adr/ADR-0003-bounded-ai-execution.md).
Each run uses the first usable provider in `AEGIS_AI_PROVIDERS` (default `anthropic,claude-code`): the Claude API
when its key file exists, otherwise the owner's subscription through `claude -p` for the members allowed to use it.
Without a usable provider every AI workload is reported as not configured; capture, search and tasks are unaffected. `remote-deploy.sh` prepares `/srv/aegis/deploy/secrets/ai` (mode 0750, group 10001),
which the application container mounts read-only at `/run/secrets/ai`. Credential files are read again when they
change, so no restart is needed; deleting a file disconnects that provider. Never put a token or key into `.env`, Git,
tickets or chat. The deployment secrets, including these files, are part of the encrypted external backup.

### Claude API with Sonnet 5

The preferred provider is the direct Claude Messages API through the Java SDK. All three workload profiles select
`claude-sonnet-5`; the API needs no Claude Code installation, subscription login or CLI process.
Both household members use their own Aegis login with the server-side API connection.

1. In [Claude Console](https://platform.claude.com/), open Billing and manually buy a small prepaid credit balance.
   API credits are separate from a Claude subscription. Check the displayed currency, minimum and taxes before
   purchase; the API prices and Aegis cost display use USD, not EUR.
2. Leave **auto-reload disabled** in Console. Aegis cannot configure billing, purchase credits, or enable reload.
   See [API billing](https://support.claude.com/en/articles/8977456-how-do-i-pay-for-my-claude-api-usage).
3. Create a dedicated Aegis API key in Console. Never paste it into chat, a shell command, Git or an issue.
4. Connect to the VM using the verified SSH command in [VM-ACCESS.md](../VM-ACCESS.md), then run:

```bash
sudo python3 /srv/aegis/source/current/ops/deploy/set-anthropic-key.py
```

Paste the key at the hidden prompt and press Enter. The script validates the basic format, writes a temporary file,
sets root:10001 ownership and mode 0640, then replaces `/srv/aegis/deploy/secrets/ai/anthropic-api-key` atomically.
The directory is mounted into the container, so the new key is readable without a restart. The script makes no API
request and never prints the key. Run it again to rotate the key; revoke the old key in Console separately.

### Subscription fallback through `claude -p`

Without an API key file, runs requested by the members in `AEGIS_AI_SUBSCRIPTION_MEMBERS` (in
`/srv/aegis/deploy/.env`, currently `cedric`) use the owner's Claude subscription. The subscription is personal, so
runs of other members, including automatic classification of their uploads, are refused as
`ai.provider.memberNotAllowed` and shown as "AI unavailable". Installing an API key switches all new runs back to the
API without a restart.

The application container never sees the subscription login. The **host runner**
(`ops/claude-runner/aegis_claude_runner.py`, systemd unit `aegis-claude-runner.service`) runs as `aegis-agent`, the
VM user who logged in with `claude`, and listens on `/srv/aegis/claude-runner/runner.sock` (group 10001, mode 0660);
the container mounts that directory at `/run/aegis-claude`. Per call the application sends model, system prompt,
prompt, JSON schema, effort, output-token allowance and timeout; the runner starts one headless `claude -p` with a
fixed command line (`--tools ""`, `--restricted`, `--safe-mode`, `--strict-mcp-config`, `--disable-slash-commands`,
`--no-session-persistence`, `--permission-mode dontAsk`, `--max-turns 3`), an empty working directory and a minimal
environment without application secrets or `ANTHROPIC_API_KEY`. It stops the CLI on timeout or when the application
closes the connection, runs at most two calls at once and logs only model, status, exit code and duration. The unit
additionally forbids privilege escalation and keeps the system and home directory read-only except the CLI's own
state. Tools and multi-turn conversations are not supported through this provider; classification needs neither.

One-time setup on the VM (the CLI login stays as it is; never copy `~/.claude` or its credentials anywhere):

```bash
sudo install -m 0644 /srv/aegis/source/current/ops/claude-runner/aegis-claude-runner.service /etc/systemd/system/
sudo systemd-analyze verify /etc/systemd/system/aegis-claude-runner.service
sudo systemctl daemon-reload
sudo systemctl enable --now aegis-claude-runner.service
journalctl -u aegis-claude-runner.service -n 20
```

`remote-deploy.sh` creates `/srv/aegis/claude-runner`. After a deployment that changes the runner, restart it with
`sudo systemctl restart aegis-claude-runner.service`. To stop using the subscription, stop and disable the unit;
the socket disappears and the provider is reported as not configured. If the CLI login expires, runs fail as
`AUTHENTICATION`; log in again as `aegis-agent` with `claude`.

Costs recorded for subscription runs are API-equivalent estimates from the reported usage, not charges. The
subscription has its own limits (`aegis.ai.provider-limits.claude-code`): a daily limit of **50 USD**
(`AEGIS_AI_SUBSCRIPTION_DAILY_COST_LIMIT`) and a budget of **0.50 USD per run** (`AEGIS_AI_SUBSCRIPTION_MAX_RUN_COST`)
for every workload. Daily limits count each provider separately, so subscription runs never use up the API's
0.25 USD and vice versa. The status page shows the limit and today's spending of the provider the member's runs use.
Subscription usage limits appear as `RATE_LIMITED` with the reset time.

### Verify and understand availability

Open Aegis > Status > Check connection as either member. All profiles share Sonnet 5, so one check makes one small
structured request, not three requests. A successful run proves authentication, model access and structured output;
it does not test document extraction quality. The check has a 0.05 USD run budget and at most two attempts.

Implemented: connection checks, persistent run status, token/cost accounting, bounded retries, cancellation,
shared household access and automatic document classification (#23, [ADR-0004](../architecture/adr/ADR-0004-automatic-document-classification.md)).
Scheduled review/task creation (#24) and document chat (#25) are **not connected** and remain marked as missing
workflows even after a successful connection check.

Classification runs automatically only for **new uploads**, after Paperless has archived them with OCR. Existing
and restored documents are never analyzed in bulk: a member starts the analysis per document (document page,
"Classify now") or for up to ten selected documents in the list. Without a key, uploads are marked "AI
unavailable" and stay fully usable. To stop automatic runs for new uploads without removing the key, set
`AEGIS_DOCUMENT_ANALYSIS_AUTOMATIC=false` in `/srv/aegis/deploy/.env` and recreate the application container;
manual requests keep working. Filenames for downloads and exports use German names for built-in types and topics
(`aegis.documents.analysis.filename-language`).

The default household daily cost limit is **0.25 USD**, with one run at a time. Classification is limited to
0.05 USD per run (one structured Sonnet 5 call at low effort; at most 20,000 characters of OCR text are sent);
review/chat profiles to 0.10 USD. When the daily limit is reached, further classifications fail visibly as
"Budget exhausted" and can be started again the next day. These are application estimates from reported usage, not a
provider balance or an exact billing ceiling: interrupted requests may be billed without recorded usage.
The provider's billing controls remain authoritative. No automatic top-up exists. The only provider fallback is the
choice at request time described above; a failing API call is never repeated on the subscription.
Models, prices and per-run budgets are in `aegis.ai` of `application.yml`.

Deployment defaults: `AEGIS_AI_PROVIDERS=anthropic,claude-code`, `AEGIS_AI_SUBSCRIPTION_MEMBERS=` (empty: nobody may
use the subscription), `AEGIS_AI_DAILY_COST_LIMIT=0.25`, `AEGIS_ASSISTANT_MEMBERS=` (empty: every member is a person;
this installation sets `aegis-dev`, see [Assistant account](#assistant-account)). Existing `.env` overrides
must match these settings; changing environment settings requires recreating the application container.
Deleting the key file disables new calls; calls already in flight may finish.

## Installable app and Android share target

The frontend ships a web app manifest (`frontend/public/manifest.webmanifest`), app icons (`frontend/public/icons/`)
and a service worker (`frontend/public/sw.js`). Over the trusted HTTPS endpoint, Chrome on Android offers
"Install app"; the installed Aegis then appears in Android's Share menu for PDF files and photos.

- A share is a multipart POST to `/share-target`. The service worker answers it without contacting the server: it
  keeps the shared files in a local inbox on the phone (Cache Storage, `aegis-share-inbox-v1`) and redirects to
  `/share`. The share page uploads them through the normal authenticated `POST /api/documents` with CSRF protection,
  so there is no second upload path. A file leaves the inbox only after the server confirmed durable storage (or
  recognized an identical existing file); a login in between, an interrupted connection or a reload loses nothing
  and uploads nothing twice. Unsaved shared files are dropped from the phone after seven days.
- If no service worker is active (e.g. after clearing Chrome's site data), the share reaches the server. The backend
  stores nothing and redirects to `/share?error=unavailable`; that page registers the worker again and asks the
  member to share once more. This redirect is the only endpoint exempt from CSRF protection.
- The share target accepts **any** file type. Chrome silently drops files whose type does not match the manifest's
  `accept` list, and Android apps often report a PDF as `application/octet-stream` or without an extension, which
  ended in an empty share. The share page decides instead: like the server, it identifies PDFs and photos (JPEG, PNG,
  WebP, HEIC) by their content signature ([ADR-0011](../architecture/adr/ADR-0011-photo-originals.md)) and rejects
  everything else with a localized message. Aegis therefore also appears in the Share menu for other files, which
  are then refused. Photos needed no manifest change, so installed apps need no update for them.
- Changing `share_target` or `accept` only takes effect after Chrome updates the installed app (WebAPK). Reinstall
  Aegis on the phone to apply such a change immediately; a service worker or page change needs no reinstall.
- The service worker caches no app assets or data and handles no other request; the app itself works without it.
  **Updates:** browsers check `sw.js` on navigation, bypassing the HTTP cache. A changed worker installs and takes
  over immediately (`skipWaiting`, `clients.claim`), so after a deployment the next app start or page load uses the
  new version. A deployment can therefore not be masked by stale cached code. Keep the inbox format of `sw.js` and
  `src/share/inbox.ts` compatible or bump the cache name together with a migration.
- **Known Chrome regression (as of 2026-09-19):** Chrome 153 on Android hands installed web apps (WebAPK) the
  share's title and text but strips every file, so the share page receives an empty multipart body. This is a false
  positive of the URI-permission check added in `[M153] Verify caller URI permissions before Web Share Target in
  TWA`; WebAPKs launch through `WebappLauncherActivity`, which did not stash verified share data. It affects any
  PWA, independent of the manifest's `accept` list (reported for Squoosh on Pixel devices:
  https://github.com/GoogleChromeLabs/squoosh/issues/1503). Until the phones run a Chrome version with the fix
  (Chrome 154 is the first candidate), sharing files into Aegis cannot work and the upload form is the way in. The
  share page names this case explicitly. A second reported variant, where the body does contain the file but
  `formData()` returns nothing, is handled by the worker's own multipart reader.
- Share target and installation are Chrome features for installed apps (WebAPK). If an installation ends up as a
  plain home-screen shortcut, Aegis does not appear in the Share menu; uninstall and install again through Chrome's
  menu. Real-device verification on both phones is tracked in issue #33.
- Icons are derived from `frontend/icons/aegis-icon-source.webp` with `python scripts/generate-icons.py` (in
  `frontend/`, needs Pillow). To change the icon, replace that file and run the script again. Installed apps pick up
  a new icon only when Chrome updates the installed app, which can take up to a day.

## Scanned pages on the phone

Photos from the camera or gallery buttons are processed in the browser
([ADR-0016](../architecture/adr/ADR-0016-scanned-photo-pages.md)). The scan worker, which bundles OpenCV.js
(`assets/scan.worker-*.js`, about 10 MB, about 3 MB with Caddy's compression), is loaded on the first page after
each app update; nothing is sent to the server before "Speichern". A scan in progress is kept on the phone in IndexedDB (database
`aegis-scan`) per member until it is saved or discarded; clearing Chrome's site data for Aegis removes it. It is
neither part of the server backup nor of an export, because the document does not exist before it is saved.

## Operations

- Status page (in the app) and `GET /api/status`: an overview of the database, Paperless, the external backup, disk
  space and AI with stable reason codes (ADR-0018). AI is not listed without credentials and reads "Noch nicht
  geprüft" after every start or deployment until its next run or a manual connection check; integrations without an
  adapter (calendar, mail, Web Push, Telegram) are not listed. The collapsed technical details hold the processing
  counts, backup and disk details, recurring jobs, the AI section (workload budgets, today's spending, the connection
  check and recent runs with cancel and retry), the exports and the running commit.
- Recurring jobs: the status page shows their health (ADR-0013). `watch-job-health` checks them every five minutes
  (`aegis.operations.job-health-watch-interval`) and sends each active member one in-app `JOB_STALE` notice per
  incident when a job becomes stale or is no longer scheduled; it waits 10 minutes after a start so that jobs due
  during a downtime can run first (ADR-0014).
- Backup and storage: the same watch reads `/srv/aegis/data/backup-state/state.json` (mounted read-only) and measures
  the data disk through `aegis.storage.data-path`. A failed, stale or interrupted backup and a disk below
  `aegis.storage.low-free-fraction` (10 %) or `aegis.storage.low-free-space` (5 GB) send one `BACKUP_FAILED` or
  `DISK_SPACE_LOW` notice per member and incident (ADR-0015).
- Logs: `sudo docker compose --env-file /srv/aegis/deploy/.env -f /srv/aegis/deploy/compose.yaml logs -f aegis`.
- Restart application containers only: `... restart aegis caddy` (records persist in PostgreSQL, ingress and
  Paperless volumes; logins persist because sessions are stored in PostgreSQL).
- Reconciliation runs every five minutes inside the application: re-schedules pending documents without a job,
  quarantines ingress files without a record under `ingress/orphans/`, removes staging copies only after
  verified archival plus seven days.

## Disk space

The VM disk (100 GiB virtual) is thin-provisioned on the Proxmox pool `local-lvm`, which is smaller than that. On
2026-09-26 the pool ran full and the VM stopped with an I/O error: every deployment had built a new image on the VM,
leaving 17.7 GB of build cache and 7.7 GB of unused images, while the application data was about 144 MB.

After a successful deployment `remote-deploy.sh` therefore runs `ops/deploy/prune-docker.sh`, which

- removes `aegis-app` images except the current one, the previous deployment's (`PREVIOUS_COMMIT`, for
  rollback) and any image a container still references (for example a stopped restore-drill container);
- removes dangling images (`docker image prune -f`); other tagged images, such as the pinned service images, stay;
- trims the build cache to 3 GB (`docker builder prune -f --keep-storage`, least recently used first, so the last
  build's Maven and npm caches stay; override with `AEGIS_BUILD_CACHE_KEEP`);
- runs `fstrim -v` on the file system of `/var/lib/docker` when its disk supports discard, so the freed blocks
  return to the thin pool immediately instead of at the weekly `fstrim.timer`. The Proxmox disk has discard enabled
  (`lsblk -D` inside the VM shows a non-zero `DISC-MAX`); without it the step is skipped with a note, and neither it
  nor the timer can return space to the pool;
- prints `docker system df`.

Every step is attempted. A failed step is reported as `Cleanup warning: …` followed by a `WARNING` that the
deployment itself succeeded; it never fails a deployment that already passed its checks.

To check the space by hand (on the VM, as `aegis-agent`):

```sh
sudo -n docker system df      # Build Cache should stay near 3 GB; Images hold the stack, one older aegis-app and tools
df -h /                       # space inside the VM; the Proxmox pool usage is only visible on the host (lvs)
```

If the build cache or unused images are large anyway (for example after builds outside `deploy.sh`), run
`sudo -n docker builder prune -f --keep-storage 3GB`, `sudo -n docker image prune -f` and `sudo -n fstrim -v /`.
Do not use `docker image prune -a` or `docker system prune -a`: they also remove the previous `aegis-app` image that
rollback needs.

## Restore drill and backup

See [BACKUP.md](BACKUP.md). `ops/backup/restore-drill.sh` runs the isolated local restore drill;
`ops/backup/backup.sh` writes to the verified external Restic destination and refuses an unmounted or
unverified destination. The tracked `ops/backup/systemd/aegis-backup.timer` runs it daily at 03:30 local time.
The external snapshot passed an isolated recovery test; see BACKUP.md for evidence and limits.
