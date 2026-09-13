#!/bin/sh
# Deploys an identified Git commit from the developer PC to the Aegis VM over SSH, credential-safe:
# the source is shipped as a git archive (no token, no private key leaves this machine).
#
# Usage: ops/deploy/deploy.sh [commit-ish]   (default: HEAD; the tree must be committed)
set -eu

COMMIT_ISH="${1:-HEAD}"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SSH_KEY="${AEGIS_SSH_KEY:-$HOME/.ssh/aegis_ed25519}"
KNOWN_HOSTS="${AEGIS_KNOWN_HOSTS:-$HOME/.ssh/aegis_known_hosts}"
TARGET="${AEGIS_TARGET:-aegis-agent@192.168.2.159}"
SSH="ssh -i $SSH_KEY -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=$KNOWN_HOSTS"
SCP="scp -q -i $SSH_KEY -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=$KNOWN_HOSTS"

cd "$REPO_ROOT"
COMMIT="$(git rev-parse --verify "$COMMIT_ISH^{commit}")"
SHORT="$(git rev-parse --short "$COMMIT")"
ARCHIVE="$(mktemp -t aegis-src-XXXXXX).tar.gz"
git archive --format=tar.gz --prefix="aegis-$COMMIT/" -o "$ARCHIVE" "$COMMIT"

echo "Deploying $COMMIT to $TARGET"
$SSH "$TARGET" "mkdir -p /srv/aegis/source/releases"
$SCP "$ARCHIVE" "$TARGET:/srv/aegis/source/releases/aegis-$COMMIT.tar.gz"
rm -f "$ARCHIVE"

$SSH "$TARGET" "set -eu
  cd /srv/aegis/source/releases
  rm -rf aegis-$COMMIT
  tar -xzf aegis-$COMMIT.tar.gz
  ln -sfn /srv/aegis/source/releases/aegis-$COMMIT /srv/aegis/source/current
  sh /srv/aegis/source/current/ops/deploy/remote-deploy.sh $COMMIT
"
echo "Deployed commit $COMMIT ($SHORT). Check http://192.168.2.159/api/system/version"
