# Export, external backup and recovery

Status on 2026-09-17: portable export, the synthetic local drill and an encrypted external Restic backup are
implemented and verified. The external snapshot was restored into an isolated PostgreSQL/Paperless/Aegis
stack, including the real invoice. Daily backup is enabled. A local export drill alone would not protect
against losing the VM or host; the external recovery evidence is recorded below.

## Export (implemented)

`POST /api/exports` (Status page button) writes one generation to `/srv/aegis/exports/generations/<UTC stamp>-<UUID>/`
and publishes it atomically (staging directory, then rename, then `LATEST` pointer). A failed generation is
discarded and never replaces a good one.

```text
<generation>/
  README.md                       Markdown index with relative links to originals and source-linked tasks
  manifest.json                   schema version, counts, missing originals, SHA-256 of every file
  data/documents.json             versioned records (stable Aegis ids, checksums, subjects, Paperless ids)
  data/tasks.json                 tasks with owner, due date, planned date and source document id
  data/members.json               members (ids, usernames, display names, locale); no credentials
  data/reviews.json               review references, source versions, lifecycle and decision audit
  readable/documents.csv          readable table
  readable/tasks.csv              readable table with source titles
  documents/originals/<date>_<title>_<id>.pdf   unchanged originals (a photo keeps its type: .jpg, .png, .webp, .heic)
```

Exports open without Aegis (PDF, images, CSV, Markdown). The searchable PDF that Paperless generates from a photo
is not exported; a restore re-archives the photo and Paperless generates it again
([ADR-0011](../architecture/adr/ADR-0011-photo-originals.md)). They are one-way views: editing the CSV changes nothing.

Records in the trash ([ADR-0010](../architecture/adr/ADR-0010-recoverable-trash.md)) are exported like all
others. Since export schema 8, `data/*.json` carries their trash record (who, when, restorable until), the readable
CSV files have a `trashedAt` column and the index marks them "in the trash". A restore keeps that state, so a
trashed record comes back trashed. Trashing or restoring never prunes a backup or an export generation; earlier
copies stay where they are.

## Isolated restore drill (implemented, synthetic data only)

```bash
# on the VM
sh /srv/aegis/source/current/ops/backup/restore-drill.sh            # latest generation
sh /srv/aegis/source/current/ops/backup/restore-drill.sh <generation>
```

The script starts a disposable Compose project `aegis-drill` with its own data directory
(`/srv/aegis/restore-drill`), its own PostgreSQL, Valkey and Paperless, bound to `127.0.0.1:18080` only, and
starts Aegis with `AEGIS_IMPORT_PATH` pointing at a copy of the generation. The application imports members
(matched by username), documents (stable ids, originals re-archived into the isolated Paperless, which assigns
new external ids) and tasks (stable ids, source links intact). `verify-drill.sh` then checks every document is
archived, every original matches its exported checksum and every task kept its source link and state. The stack
is removed afterwards unless `AEGIS_DRILL_KEEP=yes`.

The running household instance is never touched by the drill. The same import path is exercised in CI by
`RestoreIT` against PostgreSQL with an in-process archive fake.

## External backup (configured and recovery-tested)

Run `sudo -n sh /srv/aegis/source/current/ops/backup/backup.sh` inside aegis-dev for a manual backup.
The wrapper loads the restricted `backup.env` and calls `backup.py`. The script stops Aegis, Paperless and
the broker during both database dumps and file capture, then restarts only services that were running before.
It stores PostgreSQL dumps, ingress originals, Paperless data/media, broker data, Caddy CA/configuration,
exports and deployment configuration/secrets with Restic. This creates a maintenance window; do not schedule
deployments or other operator writes concurrently. It fails closed:
it needs `AEGIS_BACKUP_MOUNTPOINT` to be an actual mount point carrying the marker file
`.aegis-backup-destination`, a pre-initialized `RESTIC_REPOSITORY` canonically below that mount point and
`RESTIC_PASSWORD_FILE`. Symlink and `..` escapes are rejected. Without these it writes a failure state and
exits before service changes. Later failures preserve the previous success timestamp but show DEGRADED;
a success older than 26 hours is also DEGRADED. Concurrent backup runs are locked. Repository initialization
and retention deletion are manual; this script never runs `restic init`, `forget` or `prune`.

Regression tests mock Restic/Compose and use temporary files. They prove destination guards, writer ordering
and failure cleanup. The actual external recovery test below separately proves that a snapshot can restore
the databases, originals, source links, configuration and a working login.

### Destination and key

The owner personally formatted and attached the 1 TB disk. User-supplied Proxmox output identified it as
`BackupIntenso`, TOSHIBA MQ01ABD100, serial `7376SWFGS`, 931.5 GiB, USB port `1-3`, NTFS, UUID
`30DAE27ADAE23C2A`. The other 1 TB host disk is SATA/ext4 and was not touched. After the owner's specific
approval, only this USB device was passed through to VM 102. Inside the VM, model, serial, label and UUID
matched independently. A read-only NTFS3 mount showed only `System Volume Information` at the root, with
about 932 GiB free and 128 MiB used. It was unmounted before any write.

The VM now mounts this UUID with `ntfs3` and `nofail` at `/mnt/aegis-backup`; the fstab entry was verified by
unmounting and remounting. The marker is at the mount root. Only the new
`/mnt/aegis-backup/aegis-backup/restic` directory holds the Restic repository (ID `70388d4b72`). No existing
disk entry was removed or moved. `/srv/aegis/deploy/backup.env` and the VM password file are root-only.
The recovery password also exists outside the VM at
`C:\Users\cedri\Aegis-Recovery\restic-password.txt` with an ACL for the Windows owner only. Never print or
commit it. A second independent key copy is tracked in [issue #40](https://github.com/CedricSchmitter/Aegis/issues/40).

### Backup and restore evidence (2026-09-17)

- Manual external snapshot `7ceae7c9` succeeded; `restic check --read-data` read every pack without errors.
- Restic restored that snapshot to the root-only `/srv/aegis/restore-external-20260917` directory. Both
  PostgreSQL dumps imported into a separate, network-isolated PostgreSQL instance. The recovered Aegis,
  Paperless and broker containers started on an internal Docker network; no LAN port was published.
- The recovered databases contain 2 members (`lisa`, `cedric`), 4 documents and 3 tasks, with no broken task
  source links. All 4 restored Paperless originals match the Aegis and Paperless SHA-256 values and IDs.
  The recovered Aegis API served all 4 originals with matching checksums. A login passed after setting a
  random password **only in the isolated database**; production password hashes were not changed.
- The older export generation in the snapshot contains 3 documents and 2 tasks; all 9 manifest-listed files
  passed SHA-256 validation. The PostgreSQL/Paperless restore, not that older export, verified the real invoice.
- The isolated `/api/status` returned `CONNECTED` for the success state and `DEGRADED` for simulated failure
  and staleness. Only the isolated state file was changed during this test, then restored. The isolated
  containers were stopped; their root-only data remains available for review.
- The tracked systemd service completed a second real snapshot `bb4a6aa0` with `Result=success`.
  `aegis-backup.timer` is enabled and active, scheduled daily at 03:30 Europe/Berlin; the next run was
  reported as 2026-09-18 03:30 CEST. `Persistent=true` catches up after downtime. The service deliberately
  calls the script even if the disk is missing, allowing its failure state to reach the Status page.

To reproduce the schedule on a replacement VM after restoring the disk, key and deploy configuration, install
the tracked units as root and verify their next run:

```bash
install -m 0644 /srv/aegis/source/current/ops/backup/systemd/aegis-backup.service /etc/systemd/system/
install -m 0644 /srv/aegis/source/current/ops/backup/systemd/aegis-backup.timer /etc/systemd/system/
systemd-analyze verify /etc/systemd/system/aegis-backup.service /etc/systemd/system/aegis-backup.timer
systemctl daemon-reload
systemctl enable --now aegis-backup.timer
systemctl list-timers aegis-backup.timer
```

After each run, inspect `systemctl status aegis-backup.service`, Restic snapshots and
`/srv/aegis/data/backup-state/state.json`; the timer's enabled state alone does not prove that a backup ran.

The status source preserves the previous success timestamp on a failed run and marks failures or a success
older than 26 hours as `DEGRADED`. The Status page shows the last success and check time. No automatic
`restic forget`, `prune` or other retention deletion is configured. A disk attached to the same server does not cover fire, theft or simultaneous hardware loss.
Retention and offsite work are tracked in [issue #40](https://github.com/CedricSchmitter/Aegis/issues/40);
scheduled-run and maintenance verification are tracked in [issue #39](https://github.com/CedricSchmitter/Aegis/issues/39).

### Recovery starting point outside the VM

Keep this repository and the separate recovery password available without the Aegis VM. Mount the identified
disk on a replacement machine, then point `RESTIC_REPOSITORY` to its `aegis-backup/restic` directory and
`RESTIC_PASSWORD_FILE` to the outside-VM copy of the password. Run `restic snapshots` and restore a selected
snapshot to a **new empty directory**. The recovered tree contains `srv/aegis/backup-staging/generation-*/`
with `aegis.dump`, `paperless.dump` and `recovery.json`, plus `srv/aegis/data/` media/ingress/Caddy files,
`srv/aegis/deploy/` configuration and `srv/aegis/exports/`. Import both dumps into isolated PostgreSQL 17.11
and verify originals and links before replacing any live service data. The successful isolated rehearsal above
is evidence for this route; do not overwrite a running installation as a shortcut.
