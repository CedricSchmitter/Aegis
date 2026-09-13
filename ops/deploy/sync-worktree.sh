#!/bin/sh
# Development helper: copies the current (possibly uncommitted) tracked files to the VM for running the
# PostgreSQL/Testcontainers integration tests there. Not a deployment; see deploy.sh for that.
set -eu
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SSH_KEY="${AEGIS_SSH_KEY:-$HOME/.ssh/aegis_ed25519}"
KNOWN_HOSTS="${AEGIS_KNOWN_HOSTS:-$HOME/.ssh/aegis_known_hosts}"
TARGET="${AEGIS_TARGET:-aegis-agent@192.168.2.159}"
SSH="ssh -i $SSH_KEY -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=$KNOWN_HOSTS"
SCP="scp -q -i $SSH_KEY -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=$KNOWN_HOSTS"
cd "$REPO_ROOT"
ARCHIVE="$(mktemp -t aegis-wt-XXXXXX).tar.gz"
git ls-files -co --exclude-standard -z | tar --null -T - -czf "$ARCHIVE"
$SSH "$TARGET" "mkdir -p /srv/aegis/source/worktree && rm -rf /srv/aegis/source/worktree/*"
$SCP "$ARCHIVE" "$TARGET:/tmp/aegis-worktree.tar.gz"
$SSH "$TARGET" "tar -xzf /tmp/aegis-worktree.tar.gz -C /srv/aegis/source/worktree && rm /tmp/aegis-worktree.tar.gz"
rm -f "$ARCHIVE"
echo "Worktree synced to $TARGET:/srv/aegis/source/worktree"
