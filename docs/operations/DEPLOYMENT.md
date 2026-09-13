# Deployment on the Aegis VM (first runnable test)

Target: Proxmox VM 102 `aegis-dev`, `192.168.2.159`, user `aegis-agent` (see [VM-ACCESS.md](../VM-ACCESS.md)).
Everything below touches only this VM. Home Assistant, Mealie, the Proxmox host and the external disk stay
untouched.

## What runs

`deploy/compose.yaml` starts five containers in the Compose project `aegis`:

| Service | Image | Published port | Persistent data |
|---|---|---|---|
| caddy | caddy:2.11.4-alpine | 80 (LAN) | /srv/aegis/data/caddy |
| aegis | built from `deploy/Dockerfile` as `aegis-app:<commit>` | none | /srv/aegis/data/ingress, /srv/aegis/exports |
| postgres | postgres:17.11 | none | /srv/aegis/data/postgres (databases `aegis` and `paperless`) |
| broker | valkey/valkey:9-alpine | none | /srv/aegis/data/valkey |
| paperless | ghcr.io/paperless-ngx/paperless-ngx:3.1.3 | none | /srv/aegis/data/paperless |

Only Caddy is reachable from the LAN. PostgreSQL, Valkey, Paperless and Docker administration are not
published. Aegis talks to Paperless over the internal Compose network with a service account.

## Access mode: LAN-only HTTP preview

The first test is served at `http://192.168.2.159/` without TLS. Consequences, stated in the UI as well:

- The session cookie is `HttpOnly`, `SameSite=Lax` and not `Secure` (`AEGIS_COOKIE_SECURE=false`).
- No installable PWA, no Web Push, no secure-context claims.
- Suitable for synthetic data on the home LAN only; not for real household documents.

HTTPS follow-up: the owner selected `aegis`. A local-CA Caddyfile and Compose override are prepared; client
DNS and certificate trust remain to be established. See [HOUSEHOLD-PILOT.md](HOUSEHOLD-PILOT.md). Deployment
honors `AEGIS_HTTPS=true` from `.env`, enables the override with Secure cookies, and displays an HTTPS-specific
pilot banner. The external-backup gate remains in force even after HTTPS works.

## First-time setup

```bash
# on the VM, as aegis-agent
sh /srv/aegis/source/current/ops/deploy/init-secrets.sh
```

`init-secrets.sh` writes `/srv/aegis/deploy/.env` (database and Paperless secrets) and
`/srv/aegis/deploy/secrets/initial-members.json` (the two household logins with random initial passwords),
both mode 0600, and prepares the data directories. It never overwrites existing files.

## Deploy an identified commit

From the developer PC (uses the verified SSH identity; no token or private key is copied to the VM):

```bash
sh ops/deploy/deploy.sh <commit-ish>
```

The script ships `git archive` of the commit to `/srv/aegis/source/releases/aegis-<sha>`, links
`/srv/aegis/source/current`, then runs `ops/deploy/remote-deploy.sh` on the VM: copy compose files into
`/srv/aegis/deploy`, pin `AEGIS_COMMIT=<sha>` in `.env`, build `aegis-app:<sha>` and `docker compose up -d`.
The script updates `DEPLOYED_COMMIT` only after readiness and running-version checks pass. The running
commit is visible at `/api/system/version` and in the UI footer.

Rollback to a previously built commit: `sh /srv/aegis/source/current/ops/deploy/rollback.sh <sha>` on the VM
(application container only; database migrations are additive and are not undone).

## Onboarding the two logins

The initial passwords are only on the VM:

```bash
ssh ... aegis-agent@192.168.2.159 cat /srv/aegis/deploy/secrets/initial-members.json
```

Log in at `http://192.168.2.159/login`, then change the password under Settings. Passwords are stored as
BCrypt hashes; the JSON file only seeds accounts that do not exist yet and can be deleted after both members
changed their passwords.

## Operations

- Status page (in the app) and `GET /api/status`: database, Paperless, export, external backup and the
  deliberately unconnected integrations (calendar, mail, AI, Web Push, Telegram).
- Logs: `sudo docker compose --env-file /srv/aegis/deploy/.env -f /srv/aegis/deploy/compose.yaml logs -f aegis`.
- Restart application containers only: `... restart aegis caddy` (records persist in PostgreSQL, ingress and
  Paperless volumes; logins persist because sessions are stored in PostgreSQL).
- Reconciliation runs every five minutes inside the application: re-schedules pending documents without a job,
  quarantines ingress files without a record under `ingress/orphans/`, removes staging copies only after
  verified archival plus seven days.

## Restore drill and backup

See [BACKUP.md](BACKUP.md). `ops/backup/restore-drill.sh` runs the isolated local restore drill;
`ops/backup/backup.sh` is prepared for an external Restic destination and refuses to run until that
destination is verified.
