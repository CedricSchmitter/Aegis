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
commit is visible at `/api/system/version` and in the UI footer.

Rollback to a previously built commit: `sh /srv/aegis/source/current/ops/deploy/rollback.sh <sha>` on the VM
(application container only; database migrations are additive and are not undone).

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

## AI provider key (optional)

AI runs through the bounded execution boundary of [ADR-0003](../architecture/adr/ADR-0003-bounded-ai-execution.md).
Without a key file every AI workload is reported as not configured; capture, search and tasks are unaffected.
`remote-deploy.sh` prepares `/srv/aegis/deploy/secrets/ai` (mode 0750, group 10001), which the application
container mounts read-only at `/run/secrets/ai`.

To connect the Claude API, the owner creates an API key in the Claude Console (billed to that account) and stores it
on the VM without echoing it into the shell history:

```bash
# on the VM; paste the key, press Enter, then Ctrl-D
sudo sh -c 'umask 027; cat > /srv/aegis/deploy/secrets/ai/anthropic-api-key'
sudo chown aegis-agent:10001 /srv/aegis/deploy/secrets/ai/anthropic-api-key
sudo chmod 0640 /srv/aegis/deploy/secrets/ai/anthropic-api-key
```

The file is read again when it changes, so no restart is needed; deleting it disconnects AI. Then use
"Check connection" on the Status page: it runs one structured call per configured model and shows the result, cost
and any failure category. The household daily limit defaults to 2.00 USD (`AEGIS_AI_DAILY_COST_LIMIT` in `.env`);
models, prices and per-run budgets are in `aegis.ai` of `application.yml`. Never put the key into `.env`, Git,
tickets or chat. The deployment secrets, including this file, are part of the encrypted external backup.

## Operations

- Status page (in the app) and `GET /api/status`: database, Paperless, export, external backup, AI (not configured;
  degraded while a configured model is unverified or its latest run failed for a provider reason; otherwise
  connected) and the deliberately unconnected integrations (calendar,
  mail, Web Push, Telegram). The AI section lists workload budgets, today's spending and recent runs with cancel and
  retry.
- Logs: `sudo docker compose --env-file /srv/aegis/deploy/.env -f /srv/aegis/deploy/compose.yaml logs -f aegis`.
- Restart application containers only: `... restart aegis caddy` (records persist in PostgreSQL, ingress and
  Paperless volumes; logins persist because sessions are stored in PostgreSQL).
- Reconciliation runs every five minutes inside the application: re-schedules pending documents without a job,
  quarantines ingress files without a record under `ingress/orphans/`, removes staging copies only after
  verified archival plus seven days.

## Restore drill and backup

See [BACKUP.md](BACKUP.md). `ops/backup/restore-drill.sh` runs the isolated local restore drill;
`ops/backup/backup.sh` writes to the verified external Restic destination and refuses an unmounted or
unverified destination. The tracked `ops/backup/systemd/aegis-backup.timer` runs it daily at 03:30 local time.
The external snapshot passed an isolated recovery test; see BACKUP.md for evidence and limits.
