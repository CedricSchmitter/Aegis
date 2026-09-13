#!/bin/sh
# Run as root inside aegis-dev; no automatic repository initialization or retention deletion.
set -eu
ENV_FILE="${AEGIS_BACKUP_ENV:-/srv/aegis/deploy/backup.env}"
if [ -f "$ENV_FILE" ]; then
    set -a
    . "$ENV_FILE"
    set +a
fi
exec python3 "$(dirname "$0")/backup.py"
