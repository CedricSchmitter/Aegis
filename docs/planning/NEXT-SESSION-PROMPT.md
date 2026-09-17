# Next session: external backup and verified recovery

Continue Aegis in `D:\projekte\Aegis`. Read AGENT.md, docs/operations/HOUSEHOLD-PILOT.md,
docs/operations/BACKUP.md and docs/VM-ACCESS.md. Inspect Git, CI and the actual VM state before changes.
Do not restart completed implementation, account migration, release review or HTTPS activation.

## Completed and verified

- PR #15 delivered the independent release review and fixes; see INDEPENDENT-REVIEW.md.
- PR #18 is merged after all four CI checks passed (run 35216385600). It corrects IP-preserving HTTPS
  redirects and certificate selection for clients without SNI.
- Last independently verified application build: `e148ef8`; HTTPS configuration: `30554ee`, recorded separately
  in `/srv/aegis/deploy/HTTPS_CONFIG_COMMIT`. A newer Git main does not imply a newer deployed application.
- LAN HTTPS is active at `https://192.168.2.159`. Windows user trust, verified TLS, both account logins,
  original checksums, authorization, logout and Secure/HttpOnly/SameSite session cookies passed.
- Owner-confirmed device evidence: trusted HTTPS and login work on both Android phones; a mixer invoice was
  uploaded and its original opened on both phones. A linked task was assigned to Lisa, she received an
  in-app notice and completed it. These are user observations, not automated browser-test results.
- Accounts are `lisa` and `cedric`. Owner-requested temporary test passwords were changed through HTTPS and
  both new logins verified. Provisioning seed passwords are now stale; do not use them for live review scripts.
  Do not print or commit credentials. Individual passwords are required before further real-document use.
- One real invoice is already stored. Preserve it and all existing documents/tasks.

## Updated disk decision

The owner reports having freshly formatted the external 1 TB disk personally and connected it directly to
the server. This supersedes older notes saying it is unattached and contains previous data. Read-only checks
on 2026-09-17 found no external block or USB disk inside VM 102; only its 100 GB QEMU system disk and virtual
DVD drive are visible. The owner then supplied a Proxmox disk screenshot showing two 1 TB disks: WDC/ext4
`/dev/sdb` and Toshiba/NTFS `/dev/sdd`. The screenshot lacks the `BackupIntenso` label, USB transport and UUID;
the intended disk is not yet identified. Contents remain unverified. Do not format, repartition or delete it.

## Next concrete work

1. Identify the server-attached disk on Proxmox by model/serial, capacity, filesystem and UUID. VM 102 currently
   has no view of it; repeat read-only inspection inside the VM after any passthrough.
2. If visible only on Proxmox, explain a safe passthrough procedure specifically for VM 102 and obtain the
   missing host-change authorization or guide the owner through the UI. VM authorization is not host authorization.
3. Review the existing backup scripts, select a dedicated directory and establish encrypted external backup
   without deleting existing contents. Keep recovery-key access outside the VM.
4. Perform an actual backup and restore to an isolated target; local export drills do not satisfy this gate.
5. Establish regular backups and visible failure/staleness reporting after actual recovery verification.
6. Record evidence and reconcile issues #16/#17 with the completed device tests and remaining backup work.

## Scope

Only Aegis VM 102 (`aegis-dev`, `192.168.2.159`) is authorized for infrastructure changes. Git operations,
including commits, push/pull, PR and merge, remain authorized. Other VMs and Proxmox settings stay untouched
without specific approval. Public/internet access is explicitly deferred; local DNS is optional.
Ask only for genuinely missing decisions and continue independent work. Conversation is German; repository
content is English. Keep secrets and household document content out of Git, logs and issues.
