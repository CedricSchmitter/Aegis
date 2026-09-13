# Aegis VM access and development foundation

## Target

- Proxmox VM: 102, aegis-dev
- Reserved IPv4 address: 192.168.2.159
- User: aegis-agent, administrative access through sudo -n
- Operating system: Debian 13
- Resources: 4 vCPU, 8 GiB RAM, 100 GiB virtual disk
- Remote project directory: /srv/aegis

SSH access is scoped to this VM. No access or configuration changes were made to the Proxmox host, Home Assistant, or Mealie.

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

## Not configured yet

- Application, database containers, and Paperless
- Mail and calendar integration
- External backup destination and tested recovery
- Public web access and production household data

Restic alone is not a backup. Before relying on this system for household records, connect a separate backup destination and complete a restore test.

## Verified setup state

- Key authentication with strict host checking succeeded; sudo -n whoami returns root.
- OpenJDK 21.0.12.1, Maven 3.9.9, Docker Engine 26.1.5 (Debian package), Compose 2.26.1, and Restic 0.18.0 were checked.
- The Docker hello-world test image was downloaded and run successfully; the container was removed automatically.
- SSH, QEMU Guest Agent, Docker, and systemd-timesyncd are active.
- Time zone is Europe/Berlin; NTPSynchronized=yes was confirmed.
- Approximately 87 GiB remained free on / after installation.
- The bootstrap script passed sh -n on the VM and is available at /srv/aegis/deploy/bootstrap-vm.sh.
- No Aegis web services or database ports have been published.

This completes the development-tooling portion, not roadmap phase 1. Application scaffolding, backup, and recovery remain outstanding.
