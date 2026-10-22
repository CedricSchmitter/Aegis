# ADR-0029: The portable export refreshes itself daily and only when the data changed

Status: accepted and implemented. Date: 2026-10-22.

## Context

Issue #41 asks to keep the readable, independent export current at least daily, to publish only complete
generations and to show the last success and failure. Until now a member had to press "Export jetzt erstellen" on
the status page. Every generation copies all originals, so a daily copy of unchanged data would fill the disk and the
external backup with identical generations. The owner asked that unchanged data produce no new export.

## Decision

1. **A daily job, before the backup.** The recurring db-scheduler job `refresh-export` runs every day at
   `aegis.exports.daily-at` (default 02:30) in the household time zone, an hour before the external backup at 03:30
   copies `/srv/aegis/exports` to the backup disk. It is declared like the other recurring jobs, so its health is on
   the status page (ADR-0013) and members get a notice when it stops running (ADR-0014).
2. **The exporter is a household process.** The job runs as `Actor.householdProcess("exporter")`, never as a
   member. The export queries of documents (records, originals, catalog, page derivations), tasks, contracts and the
   review inbox, and the member directory, are opened to household processes through `requireReader`, as ADR-0009
   did for the review reconciler. They only read; a household process can still not change a record. Only the
   exporter may run the daily export, and a manual export still requires a member.
3. **A fingerprint of the exported data decides.** Before writing, the export reads every record it would write and
   computes `dataFingerprint`: the SHA-256 of a canonical JSON (compact, map keys sorted, every list ordered by id) of
   all entries, the export schema version and the producer (the running commit). Originals enter through their
   SHA-256 checksum, which is fixed for a document's lifetime, so they are not read for the comparison. The
   fingerprint is stored in `manifest.json` together with `trigger` (`MANUAL` or `SCHEDULED`). When the fingerprint
   equals that of the latest generation, the run writes nothing. A manual export with the same data counts as
   current. Hashing the written files instead would never match, because every generation records its own name and
   time, and it would read every original each night.
4. **Missing originals are retried.** When the latest generation lacks an original, for example because the archive
   was unreachable, each run tries to read those originals; once one can be read, a new generation is written even if
   the data is otherwise unchanged.
5. **Only complete generations, a bounded history.** Generations are still staged and published atomically; a failed
   run publishes nothing and the previous generation stays the latest. After a new scheduled generation, scheduled
   generations beyond the newest `aegis.exports.scheduled-retention` (default 14, 0 keeps all) are removed from the
   export directory. Manual generations and the latest one are never removed; older copies remain in the external
   backup snapshots.
6. **Status.** `scheduled-run.json` in the export directory records when a scheduled run last finished and last
   failed. The status overview has an `export` line: OK (`export.ok`) with the newest generation's time and the time
   of the last confirmation, a problem when the last run failed after the last success (`export.failed`) or no run
   finished for two days (`export.stale`), and unchecked before the first run (`export.pending`).

## Consequences

- A household that changes nothing gets no new generation and no extra backup growth; the status page still shows
  that the export was confirmed current last night.
- A deployment changes the producer, so the first night after a deployment writes one new generation even without a
  data change. This keeps the files in step with format changes that do not raise the schema version.
- Exporting at night means a change during the day is exported the next night, not right away. A member can still
  create an export at once on the status page.
- Older Aegis versions read the new manifest, because unknown fields are ignored; manifests without a fingerprint
  never match, so the first run after this change writes one generation.
