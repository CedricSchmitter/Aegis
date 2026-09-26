#!/bin/sh
# Runs the CI jobs (backend verify, frontend checks, browser smoke against a compose stack with real Paperless) for a
# committed tree on the Aegis VM, for times when GitHub Actions minutes are used up. Same commands as
# .github/workflows; see ops/ci/remote-checks.sh for what runs and how it stays apart from the household stack.
# The commit is shipped as a git archive like deploy.sh does; no token or private key leaves this machine. The VM
# side scripts come from this checkout, so commits that predate them can be checked too.
#
# Usage: ops/ci/vm-checks.sh [commit-ish] [backend] [frontend] [smoke]   (default: HEAD and all three jobs)
# All three jobs take about 20 minutes. The full log stays on the VM in /srv/aegis/source/ci-runs/<sha>.log.
set -eu

COMMIT_ISH="${1:-HEAD}"
[ $# -gt 0 ] && shift
JOBS="$*"
TOOLS="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$TOOLS/../.." && pwd)"
SSH_KEY="${AEGIS_SSH_KEY:-$HOME/.ssh/aegis_ed25519}"
KNOWN_HOSTS="${AEGIS_KNOWN_HOSTS:-$HOME/.ssh/aegis_known_hosts}"
TARGET="${AEGIS_TARGET:-aegis-agent@192.168.2.159}"
SSH="ssh -i $SSH_KEY -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=$KNOWN_HOSTS"
SCP="scp -q -i $SSH_KEY -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=$KNOWN_HOSTS"

cd "$REPO_ROOT"
COMMIT="$(git rev-parse --verify "$COMMIT_ISH^{commit}")"
SHORT="$(git rev-parse --short "$COMMIT")"
RUN="/srv/aegis/source/ci-runs/$SHORT"
ARCHIVE="$(mktemp -t aegis-ci-XXXXXX).tar.gz"
git -c core.autocrlf=false archive --format=tar.gz --prefix="src/" -o "$ARCHIVE" "$COMMIT"

echo "Running CI jobs (${JOBS:-backend frontend smoke}) for $COMMIT on $TARGET"
$SSH "$TARGET" "set -eu; sudo -n rm -rf $RUN; mkdir -p $RUN/ci"
$SCP "$ARCHIVE" "$TARGET:$RUN/src.tar.gz"
$SCP "$TOOLS/remote-checks.sh" "$TOOLS/compose.vm-ci.yaml" "$TARGET:$RUN/ci/"
rm -f "$ARCHIVE"

set +e
$SSH "$TARGET" "cd $RUN && tar -xzf src.tar.gz && rm src.tar.gz \
  && sh $RUN/ci/remote-checks.sh $RUN $JOBS > $RUN.log 2>&1; status=\$?; \
  sed -n '/^=== Summary/,\$p' $RUN.log; exit \$status"
STATUS=$?
set -e
if [ "$STATUS" -eq 0 ]; then
  echo "All CI jobs passed for $SHORT. Log: $TARGET:$RUN.log"
else
  echo "CI jobs failed for $SHORT (exit $STATUS). Log: $TARGET:$RUN.log"
fi
exit "$STATUS"
