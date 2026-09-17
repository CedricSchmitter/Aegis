# Export, restore drill and backup preparation

Status after the first runnable test: portable export and an isolated local restore drill are implemented and
verified. **An external backup is not configured.** The status page reports `externalBackup: NOT_CONFIGURED`
until a verified destination and a successful run exist. A local drill does not protect against losing the VM or
the Proxmox host.

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

Run `sudo -n sh /srv/aegis/source/current/ops/backup/backup.sh` inside aegis-dev after destination setup.
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
and failure cleanup, not recovery from an actual disk. After the first external snapshot, restore its dumps,
files, secrets and configuration into an isolated environment and verify original checksums, source links,
accounts and login before scheduling daily backups.

### External 1 TB disk: non-destructive checklist

On 2026-09-17 the owner reported personally formatting the disk and connecting it directly to the server.
This supersedes earlier unattached/nonempty reports. A read-only check inside VM 102 found no external block
or USB disk. A user-supplied Proxmox disk screenshot shows two 1 TB disks: `/dev/sdb` (WDC, ext4 partition)
and `/dev/sdd` (Toshiba, NTFS). The screenshot does not show the `BackupIntenso` label, USB transport, UUID or
contents, so neither disk is yet identified as the backup target. Restic 0.18.0 is installed, but `backup.env`,
the backup state file and an Aegis backup timer are absent. Nothing below formats, repartitions or deletes data.

1. Identify the 1 TB device on the Proxmox host by size, model, serial, filesystem and UUID. Obtain specific
   authorization before changing host configuration. Pass through only this identified USB device to VM 102;
   do not alter other VMs or Proxmox storage. A host bind mount is not directly usable by a VM.
2. Inspect before use, read-only: `lsblk -f`, `sudo blkid`, `sudo smartctl -H /dev/sdX` (install
   `smartmontools` only inside the VM). Record filesystem type, label, free space and health.
3. Mount read-only first (`mount -o ro`) and list the top-level contents. Choose a dedicated subdirectory such
   as `aegis-backup/` without deleting or moving anything already present.
4. Create the marker file `.aegis-backup-destination` in the mount root after checking that path is unused;
   mount read-write by the verified UUID with `nofail` in `/etc/fstab` so a missing disk does not block boot.
5. Generate the Restic password, store a copy outside the VM (password manager and paper), point
   `RESTIC_PASSWORD_FILE` at a 0600 file on the VM, initialize only the dedicated empty repository directory
   with `restic init`, then run `backup.sh` once manually and inspect `restic snapshots`.
6. Restore the actual external Restic snapshot into a separate directory and isolated stack; verify database
   dumps, originals and checksums, member identities, task source links, configuration and login. The local
   export drill alone does not meet this gate. Only after this succeeds add a daily timer and verify that
   failed or stale runs appear on the Status page. Do not enable automatic `forget` or `prune`.

Offsite copies remain a later improvement; a nearby disk does not protect against fire, theft or a failure
that takes both devices.
