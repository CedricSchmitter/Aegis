# Next session: household pilot and backup monitoring

Continue Aegis in `D:\projekte\Aegis`. Read AGENT.md, docs/operations/HOUSEHOLD-PILOT.md,
docs/operations/BACKUP.md and docs/VM-ACCESS.md. Inspect Git, CI and the actual VM state before changes.
Do not restart the completed account migration, HTTPS activation, USB identification or recovery drill.

## Completed and verified on 2026-09-17

- PR #18 merged after all four CI checks passed; it corrected IP-preserving HTTPS redirects and certificate
  selection for clients without SNI. PR #19 merged after all four CI checks passed (run 35237838666).
  Its merge commit `e65a1b6` is deployed and was independently verified through trusted HTTPS and in VM 102.
  HTTPS configuration commit `30554ee` is recorded separately in
  `/srv/aegis/deploy/HTTPS_CONFIG_COMMIT`. Verify the actual deployed commit before citing a newer one.
- Trusted LAN HTTPS at `https://192.168.2.159` works on the PC and both Android phones. The owner confirmed
  phone upload, original access, task assignment, in-app notice and completion. These are owner observations,
  distinct from the independently verified TLS, login, checksum, authorization and session-cookie checks.
- Accounts are `lisa` and `cedric`. The earlier change to temporary test passwords and logins was independently
  verified through HTTPS; provisioning passwords are stale. The owner later confirmed both accounts changed
  to individual passwords and both fresh logins worked. The later credential change is owner-reported, not
  independently agent-verified. One real mixer invoice is already stored; preserve all data.
- The owner reported personally formatting and attaching the 1 TB disk. Owner-supplied Proxmox output
  identified `BackupIntenso` as USB port `1-3`, TOSHIBA MQ01ABD100, serial `7376SWFGS`, NTFS, UUID
  `30DAE27ADAE23C2A`. After explicit approval, the owner passed only that port through to VM 102. Direct
  VM checks confirmed the device identifiers. A read-only NTFS3 mount showed only `System Volume Information`
  before any backup directory was created. The separate host SATA/ext4 1 TB disk was untouched.
- The USB disk is mounted by UUID at `/mnt/aegis-backup`; the dedicated encrypted Restic repository is
  `/mnt/aegis-backup/aegis-backup/restic`. An outside-VM recovery password copy exists at
  `C:\Users\cedri\Aegis-Recovery\restic-password.txt` with owner-only ACL. Never print or commit it.
- External snapshot `7ceae7c9` passed `restic check --read-data`. Its two PostgreSQL dumps and files were
  restored to an isolated environment. The recovered Aegis/Paperless stack served all four originals with
  matching SHA-256 checksums, preserved both members and task links, and passed login using a credential
  changed only in the isolated database. The recovered state included the real invoice. A second snapshot
  `bb4a6aa0` succeeded via the tracked systemd service. No production passwords or data were changed.
- `aegis-backup.timer` is enabled for 03:30 Europe/Berlin daily. Its first scheduled run is not yet observed.
  A failed run preserves the previous success timestamp and reports DEGRADED; success older than 26 hours
  also reports DEGRADED. The isolated status API passed success, failure and staleness simulations.
- The deployed Status page bundle contains the new status-aware backup warning. Issue #17 was closed with the
  external recovery evidence. The recovery-key copy on the PC should gain a second independent copy later.

## Next concrete work

1. After the first scheduled 03:30 run, inspect `systemctl status aegis-backup.service`, `systemctl list-timers`,
   Restic snapshots, `/srv/aegis/data/backup-state/state.json` and the app Status page. Record actual evidence;
   timer activation alone does not prove a future run.
2. Run the small household pilot with representative workflows. Arrange a second recovery-key copy and an
   offsite backup later. The same-server USB disk does not cover fire, theft or simultaneous hardware loss.

## Scope

Only Aegis VM 102 (`aegis-dev`, `192.168.2.159`) is authorized for infrastructure changes. The specific
Proxmox USB passthrough was separately approved and completed; no other host change is authorized. Git
operations, including commit, push, PR and merge, remain authorized. Other VMs stay untouched. Public and
internet access remain explicitly excluded; local DNS is optional. Conversation is German; repository
content is English. Keep secrets and household document content out of Git, logs and issues.
