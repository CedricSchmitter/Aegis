# Aegis VM access and development foundation

## Target

- Proxmox VM: 102, aegis-dev
- Reserved IPv4 address: a private LAN address, written as `<aegis-host>` in the documentation. The concrete
  address is the default `AEGIS_TARGET` of [deploy.sh](../ops/deploy/deploy.sh).
- User: aegis-agent, administrative access through sudo -n
- Operating system: Debian 13
- Resources: 4 vCPU, 8 GiB RAM, 100 GiB virtual disk
- Remote project directory: /srv/aegis

SSH access is scoped to this VM. The owner separately authorized USB passthrough for `BackupIntenso` to VM 102 through Proxmox; Home Assistant and Mealie were not changed.

## Connect from the owner's Windows PC

```powershell
ssh -i $HOME\.ssh\aegis_ed25519 -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=$HOME\.ssh\aegis_known_hosts aegis-agent@<aegis-host>
```

Add `-o BatchMode=yes` for automated commands.

The private key remains in the personal .ssh directory outside the repository. Its passphrase was removed with explicit owner approval. Do not include private key files in Git or readable household exports.

The server host key was verified against the VM console and is pinned in `aegis_known_hosts`
(`ssh-keygen -lf <file>` shows the fingerprint). Fingerprints are not repeated in this repository.

If the host key changes, investigate and verify it through the VM console. Do not disable strict host verification.

## Development tooling

The baseline uses Debian packages for Docker/Compose, OpenJDK 21, Maven, Git, Restic, and rsync. Docker currently runs through sudo. No unauthenticated Docker TCP access is configured.

```text
/srv/aegis/
  source/           Project working copy
  deploy/           Deployment configuration
  data/             Persistent service data; assign permissions per service
  exports/          Readable outputs
  backup-staging/   Consistent artifacts prepared for backup
```

Reproduce installation with [bootstrap-vm.sh](../ops/bootstrap-vm.sh), only inside the intended Aegis VM.

## Current application state

The application stack is deployed on this VM:
Caddy publishes LAN ports 80 and 443; Aegis, PostgreSQL, Paperless and Valkey run privately. The independent review
verified 81 GiB free on the VM at that time. On 2026-09-17 the owner authorized and performed passthrough of
the identified `BackupIntenso` USB port only to VM 102. The VM independently verified the disk's model,
serial number, NTFS label and filesystem UUID; it now mounts the disk at
`/mnt/aegis-backup`. The separate host SATA/ext4 disk was not touched. See [BACKUP.md](operations/BACKUP.md)
for inspection, backup and recovery procedures.

For integration availability, use the application's Status page; for dated verification, use the relevant issues,
pull requests and CI runs.
Check current capacity and service versions before maintenance; historical free-space measurements are
not a current capacity guarantee.
