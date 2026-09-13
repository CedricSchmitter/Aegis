# Export, restore drill and backup preparation

Status after the first runnable test: portable export and an isolated local restore drill are implemented and
verified. **An external backup is not configured.** The status page reports `externalBackup: NOT_CONFIGURED`
until a verified destination and a successful run exist. A local drill does not protect against losing the VM or
the Proxmox host.

## Export (implemented)

`POST /api/exports` (Status page button) writes one generation to `/srv/aegis/exports/generations/<UTC stamp>/`
and publishes it atomically (staging directory, then rename, then `LATEST` pointer). A failed generation is
discarded and never replaces a good one.

```text
<generation>/
  README.md                       Markdown index with relative links to originals and source-linked tasks
  manifest.json                   schema version, counts, missing originals, SHA-256 of every file
  data/documents.json             versioned records (stable Aegis ids, checksums, subjects, Paperless ids)
  data/tasks.json                 tasks with owner, due date, planned date and source document id
  data/members.json               members (ids, usernames, display names, locale); no credentials
  readable/documents.csv          readable table
  readable/tasks.csv              readable table with source titles
  documents/originals/<date>_<title>_<id>.pdf   unchanged originals
```

Exports open without Aegis (PDF, CSV, Markdown). They are one-way views: editing the CSV changes nothing.

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

## External backup (prepared, not configured)

`ops/backup/backup.sh` produces consistent artifacts (pg_dump of `aegis` and `paperless`, ingress originals,
Paperless media, exports, deployment configuration and secrets) and stores them with Restic. It fails closed:
it needs `AEGIS_BACKUP_MOUNTPOINT` to be an actual mount point carrying the marker file
`.aegis-backup-destination`, `RESTIC_REPOSITORY` below that mount point and `RESTIC_PASSWORD_FILE`. Without
these it writes `destinationVerified: false` into the state file that the status page reads, and exits.

### External 1 TB disk: non-destructive checklist

The disk already contains data and may eventually stay attached. Nothing below formats, repartitions or
mounts over existing data.

1. Decide where it connects: to the Proxmox host (then passed to VM 102 as a USB device or a bind-mounted
   directory) or directly to the VM. Do not change Proxmox storage configuration for this.
2. Inspect before use, read-only: `lsblk -f`, `sudo blkid`, `sudo smartctl -H /dev/sdX` (install
   `smartmontools` only inside the VM). Record filesystem type, label, free space and health.
3. Mount read-only first (`mount -o ro`) and list the top-level contents. Choose a dedicated subdirectory such
   as `aegis-backup/` next to the existing data; never delete or move what is there.
4. Create the marker file `.aegis-backup-destination` in the mount root only after the owner confirms the
   directory; mount read-write with `nofail` in `/etc/fstab` so a missing disk does not block boot.
5. Generate the Restic password, store a copy outside the VM (password manager and paper), point
   `RESTIC_PASSWORD_FILE` at a 0600 file on the VM, run `backup.sh` once manually, then `restic snapshots`.
6. Only then add a timer (systemd or cron) for daily runs and rehearse a restore from the Restic repository
   into the isolated drill stack.

Offsite copies remain a later improvement; a nearby disk does not protect against fire, theft or a failure
that takes both devices.
