#!/bin/sh
# Run only inside the dedicated Aegis VM as a sudo-capable user.
set -eu

if [ "$(hostname)" != "aegis-dev" ]; then
    echo 'Refusing bootstrap: expected host aegis-dev.' >&2
    exit 1
fi

getent passwd aegis-agent >/dev/null
sudo -n apt-get update
sudo -n env DEBIAN_FRONTEND=noninteractive apt-get install -y \
    ca-certificates curl git openssh-server qemu-guest-agent \
    docker.io docker-compose openjdk-21-jdk-headless maven \
    restic rsync systemd-timesyncd
sudo -n systemctl enable --now docker ssh systemd-timesyncd
sudo -n systemctl start qemu-guest-agent
sudo -n install -d -m 0750 -o aegis-agent -g aegis-agent \
    /srv/aegis /srv/aegis/source /srv/aegis/deploy \
    /srv/aegis/exports /srv/aegis/backup-staging
sudo -n install -d -m 0750 /srv/aegis/data
