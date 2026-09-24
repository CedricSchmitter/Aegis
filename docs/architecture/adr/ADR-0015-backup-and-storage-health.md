# ADR-0015: The backup reports a reason, disk space is measured, and members are notified once per incident

Status: accepted and implemented for issue #100. Date: 2026-09-24.

## Context

The external backup (ops/backup, [BACKUP.md](../../operations/BACKUP.md)) runs on the host at 03:30 and writes a
state file that the application reads. Until now a failure was only recorded as `"Backup failed: <exception class>"`,
so a missing USB disk, a missing destination marker and a failed repository check all looked alike. A backup that was
still running showed as a failure. Nobody saw disk space, and a backup outage was visible only to someone who opened
the status page. [ADR-0014](ADR-0014-stale-job-notices.md) already notifies members once per incident when a
recurring job stops running.

## Decision

1. **Stable failure codes from the script.** `backup.py` records `failureCode` instead of free text:
   `destination.notMounted`, `destination.markerMissing`, `destination.changed`, `repository.outsideMount`,
   `repository.notInitialized`, `repository.unreadable`, `password.missing`, `capture.failed`,
   `services.restartFailed`, `check.failed`, or `unexpected` for anything else. It never records command output.
   The file carries `schemaVersion: 2`, and the script clears the old free-text `error` on every run. When the
   state file still has the old format, the reader maps `"Backup in progress"` to a running backup and any other
   text to the code `unknown`.
2. **A running backup is its own state.** The script sets `runningSince` after it takes its lock and before it stops
   any service, and clears it when the run ends. A second run that finds the lock taken exits without touching the
   state. The systemd unit stops a run after two hours, so a backup that still counts as running after
   **three hours** is `INTERRUPTED`, for example because the VM lost power during the run.
3. **Backup states.** The application derives one state from the file, checking in this order: `RUNNING` or
   `INTERRUPTED`, then `FAILED` (a failure code is set), then `NOT_CONFIGURED` (no verified destination or no
   success yet), then `STALE` (the last success is older than 26 hours), otherwise `OK`. If there is no file the
   state is `NOT_CONFIGURED`. If the file exists but cannot be read the state is `UNKNOWN` with the code
   `state.unreadable`. The integration list keeps its `externalBackup` entry, derived from this state, for
   compatibility.
4. **Disk space.** The application measures its data storage (`aegis.storage.data-path`, the ingress bind mount of
   `/srv/aegis/data`) through `FileStore`, without shell calls. The backup disk is not mounted into the container,
   so the script records its `totalBytes`/`freeBytes` after every attempt while it is mounted. The status page shows
   when each disk was measured. A disk is `LOW` when it has less than the higher of
   `aegis.storage.low-free-fraction` (10 %) and `aegis.storage.low-free-space` (5 GB) free. The owner chose these
   limits on 2026-09-24.
5. **Incidents and notices.** The existing `watch-job-health` run also watches the backup and each measured disk.
   The job keeps its name so that its persisted schedule stays valid. Incidents live in
   `storage_health_incidents`, at most one open incident per subject (`backup`, `disk:data`, `disk:backup`):
   - `FAILED`, `STALE`, `INTERRUPTED` and `UNKNOWN` open a backup incident. Only `OK` closes it.
   - `RUNNING` and `NOT_CONFIGURED` neither open nor close an incident.
   - A `LOW` disk opens a disk incident. The incident closes only once the free space is at least 10 % above the
     limit, so a disk that hovers around the limit does not notify every few minutes.
   While an incident is open, each check publishes `BACKUP_FAILED` (params `state`, `reason`, `lastSuccessAt`) or
   `DISK_SPACE_LOW` (params `disk`, `freeBytes`, `totalBytes`) to every active member. The deduplication key is
   `storage-health:<incident id>` and the notice links to the status page. The notice keeps what was observed when
   the incident opened. A changing failure code within one outage is therefore still one notice. The steps are
   idempotent as in ADR-0014, and one failing subject does not hide the others. There is no start grace: the backup
   runs on the host, and disk space does not depend on the application having started.
6. **Presentation.** The status page has a "Backup and storage" section. It shows a localized reason and a short hint
   for each failure code ("Is the USB disk connected?"), the last success, the last attempt and each disk's free
   space against its limit. The failure codes stay stable English values in the API and in the notices.

## Consequences

- A missing USB disk is reported with a reason at 03:30, and both members get one notice. After the disk is
  reconnected, the next successful run closes the incident.
- If the backup timer stops, members are notified about 26 hours after the last success, within one watch interval.
- A backup in progress no longer shows as a failure. The application is stopped during the capture anyway.
- The state file must stay readable by the application's group, and its old format is still understood.
- Incidents are operational state, not household data. They are not exported, and a restore starts without them.
- Scheduled exports (#41), recovery objectives (#39) and Web Push delivery (#28) remain separate.
- Tests: `test_backup.py` covers each failure code, the running marker, the untouched state of a concurrent run and
  the disk capacity. Unit tests with a controlled clock cover the state rules, the disk limits with their recovery
  margin, and the opening, deduplication and closing of incidents. `StorageHealthWatchIT` covers concurrent opening
  and the notices to both members against PostgreSQL, using a temporary state file.
