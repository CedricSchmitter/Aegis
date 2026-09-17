# Aegis VM access and development foundation

## Target

- Proxmox VM: 102, aegis-dev
- Reserved IPv4 address: 192.168.2.159
- User: aegis-agent, administrative access through sudo -n
- Operating system: Debian 13
- Resources: 4 vCPU, 8 GiB RAM, 100 GiB virtual disk
- Remote project directory: /srv/aegis

SSH access is scoped to this VM. The owner separately authorized USB passthrough for `BackupIntenso` to VM 102 through Proxmox; Home Assistant and Mealie were not changed.

## Connect from this Windows PC

```powershell
ssh -i C:\Users\cedri\.ssh\aegis_ed25519 -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=C:\Users\cedri\.ssh\aegis_known_hosts aegis-agent@192.168.2.159
```

Add `-o BatchMode=yes` for automated commands.

The private key remains in the personal .ssh directory outside the repository. Its passphrase was removed with explicit owner approval. Do not include private key files in Git or readable household exports.

Verified server fingerprint, checked against the VM console:

```text
ED25519 SHA256:K9ggmxG5kX94EhiY/psFbP9CCWxQwx3F2xy6jNCs0mQ
```

Project public-key fingerprint:

```text
ED25519 SHA256:b4XOrh7SJnllBiyXs/mRSfhzJORKl3p2tcflPokp7mo
```

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

The original tooling-only notes below are historical. The first application stack is now deployed on this VM:
Caddy publishes LAN ports 80 and 443; Aegis, PostgreSQL, Paperless and Valkey run privately. The independent review
verified 81 GiB free on the VM at that time. On 2026-09-17 the owner authorized and performed passthrough of
the identified `BackupIntenso` USB port only to VM 102. The VM independently verified the Toshiba model,
serial `7376SWFGS`, NTFS label and UUID `30DAE27ADAE23C2A`; it now mounts the disk at
`/mnt/aegis-backup`. The separate host SATA/ext4 disk was not touched. See [BACKUP.md](operations/BACKUP.md)
for inspection, backup and recovery evidence and
[independent review](planning/INDEPENDENT-REVIEW.md) for release and CI evidence.

## Not configured yet

- Mail and calendar integration
- Public web access

The external Restic repository passed a full read check and isolated recovery. A daily backup timer is enabled;
the first scheduled run has not yet been observed. An offsite copy remains future work.

## Historical tooling setup evidence (before application deployment)

- Key authentication with strict host checking succeeded; sudo -n whoami returns root.
- OpenJDK 21.0.12.1, Maven 3.9.9, Docker Engine 26.1.5 (Debian package), Compose 2.26.1, and Restic 0.18.0 were checked.
- The Docker hello-world test image was downloaded and run successfully; the container was removed automatically.
- SSH, QEMU Guest Agent, Docker, and systemd-timesyncd are active.
- Time zone is Europe/Berlin; NTPSynchronized=yes was confirmed.
- Approximately 87 GiB remained free on / after installation.
- The bootstrap script passed sh -n on the VM and is available at /srv/aegis/deploy/bootstrap-vm.sh.
- No Aegis web services or database ports have been published.

These observations describe the initial tooling setup only. Application deployment and a local export restore
drill have since been completed, followed by external backup and recovery from `BackupIntenso`.
