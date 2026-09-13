#!/bin/sh
# Consistent Aegis backup to a Restic repository on a verified external destination.
#
# Prepared, NOT configured: it refuses to run until a destination is mounted and carries the expected marker
# file, so it never writes into an unmounted directory. See docs/operations/BACKUP.md for the external-disk
# checklist. A local restore drill (ops/backup/restore-drill.sh) is not an external backup.
#
# Required environment (typically from /srv/aegis/deploy/backup.env, mode 0600):
#   AEGIS_BACKUP_MOUNTPOINT   mount point of the external destination, e.g. /mnt/aegis-backup
#   AEGIS_BACKUP_MARKER       marker file that must exist on the mounted destination, e.g. .aegis-backup-destination
#   RESTIC_REPOSITORY         restic repository, e.g. /mnt/aegis-backup/restic
#   RESTIC_PASSWORD_FILE      file with the repository password (keep a copy outside this VM)
set -eu

DEPLOY_DIR="${AEGIS_DEPLOY_DIR:-/srv/aegis/deploy}"
DATA_DIR="${AEGIS_DATA_DIR:-/srv/aegis/data}"
EXPORTS_DIR="${AEGIS_EXPORTS_DIR:-/srv/aegis/exports}"
STAGING_DIR="${AEGIS_BACKUP_STAGING:-/srv/aegis/backup-staging}"
STATE_FILE="${AEGIS_BACKUP_STATE_FILE:-$DATA_DIR/backup-state/state.json}"
ENV_FILE="${AEGIS_BACKUP_ENV:-$DEPLOY_DIR/backup.env}"

if [ -f "$ENV_FILE" ]; then
    # shellcheck disable=SC1090
    . "$ENV_FILE"
fi

fail_closed() {
    echo "backup: $1" >&2
    mkdir -p "$(dirname "$STATE_FILE")"
    printf '{"destinationVerified": false, "lastAttemptAt": "%s", "error": "%s"}\n' \
        "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" > "$STATE_FILE.tmp" && mv "$STATE_FILE.tmp" "$STATE_FILE"
    exit 2
}

[ -n "${AEGIS_BACKUP_MOUNTPOINT:-}" ] || fail_closed "AEGIS_BACKUP_MOUNTPOINT not set: external backup not configured"
[ -n "${RESTIC_REPOSITORY:-}" ] || fail_closed "RESTIC_REPOSITORY not set"
[ -n "${RESTIC_PASSWORD_FILE:-}" ] && [ -r "$RESTIC_PASSWORD_FILE" ] || fail_closed "RESTIC_PASSWORD_FILE missing"
mountpoint -q "$AEGIS_BACKUP_MOUNTPOINT" || fail_closed "$AEGIS_BACKUP_MOUNTPOINT is not a mount point"
[ -f "$AEGIS_BACKUP_MOUNTPOINT/${AEGIS_BACKUP_MARKER:-.aegis-backup-destination}" ] \
    || fail_closed "marker file missing on destination; refusing to write"
case "$RESTIC_REPOSITORY" in
    "$AEGIS_BACKUP_MOUNTPOINT"/*) ;;
    *) fail_closed "RESTIC_REPOSITORY must lie below the verified mount point" ;;
esac

COMPOSE="sudo -n docker compose --env-file $DEPLOY_DIR/.env -f $DEPLOY_DIR/compose.yaml"
mkdir -p "$STAGING_DIR"
chmod 0750 "$STAGING_DIR"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"

echo "backup: dumping databases"
$COMPOSE exec -T postgres pg_dump -U aegis -d aegis --no-owner -Fc > "$STAGING_DIR/aegis-$STAMP.dump"
$COMPOSE exec -T postgres pg_dump -U aegis -d paperless --no-owner -Fc > "$STAGING_DIR/paperless-$STAMP.dump"
cp "$DEPLOY_DIR/compose.yaml" "$DEPLOY_DIR/Caddyfile" "$STAGING_DIR/"
cp "$DEPLOY_DIR/DEPLOYED_COMMIT" "$STAGING_DIR/DEPLOYED_COMMIT" 2>/dev/null || true

echo "backup: restic init/backup"
restic snapshots >/dev/null 2>&1 || restic init
restic backup --tag aegis --tag "$STAMP" \
    "$STAGING_DIR" \
    "$DATA_DIR/ingress" \
    "$DATA_DIR/paperless/media" \
    "$EXPORTS_DIR" \
    "$DEPLOY_DIR/.env" "$DEPLOY_DIR/secrets"
restic forget --keep-daily 14 --keep-weekly 8 --keep-monthly 12 --prune
restic check --read-data-subset=5%

rm -f "$STAGING_DIR"/*.dump
mkdir -p "$(dirname "$STATE_FILE")"
printf '{"destinationVerified": true, "lastSuccessAt": "%s", "repository": "%s"}\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$RESTIC_REPOSITORY" > "$STATE_FILE.tmp" && mv "$STATE_FILE.tmp" "$STATE_FILE"
echo "backup: completed at $STAMP"
