#!/bin/sh
# Generates server-side secrets for the Aegis stack once. Run on the Aegis VM as aegis-agent.
# Existing files are never overwritten; delete them deliberately if you want new secrets.
set -eu

if [ "$(hostname)" != "aegis-dev" ]; then
    echo 'Refusing: expected host aegis-dev.' >&2
    exit 1
fi

DEPLOY_DIR="${AEGIS_DEPLOY_DIR:-/srv/aegis/deploy}"
DATA_DIR="${AEGIS_DATA_DIR:-/srv/aegis/data}"
EXPORTS_DIR="${AEGIS_EXPORTS_DIR:-/srv/aegis/exports}"
ENV_FILE="$DEPLOY_DIR/.env"
MEMBERS_FILE="$DEPLOY_DIR/secrets/initial-members.json"

random() {
    # URL-safe random string without padding; length in bytes before encoding.
    head -c "$1" /dev/urandom | base64 | tr -d '\n=' | tr '+/' '-_'
}

mkdir -p "$DEPLOY_DIR/secrets"
chmod 0700 "$DEPLOY_DIR/secrets"

if [ -f "$ENV_FILE" ]; then
    echo "Keeping existing $ENV_FILE"
else
    umask 077
    cat > "$ENV_FILE" <<EOF
AEGIS_COMMIT=dev
AEGIS_DATA_DIR=$DATA_DIR
AEGIS_EXPORTS_DIR=$EXPORTS_DIR
AEGIS_DB_PASSWORD=$(random 24)
PAPERLESS_DB_PASSWORD=$(random 24)
PAPERLESS_SECRET_KEY=$(random 48)
PAPERLESS_ADMIN_USER=aegis-service
PAPERLESS_ADMIN_PASSWORD=$(random 24)
EOF
    echo "Wrote $ENV_FILE"
fi

if [ -f "$MEMBERS_FILE" ]; then
    echo "Keeping existing $MEMBERS_FILE"
else
    umask 077
    cat > "$MEMBERS_FILE" <<EOF
{"members": [
  {"username": "anna", "displayName": "Anna", "initialPassword": "$(random 15)", "locale": "DE"},
  {"username": "ben", "displayName": "Ben", "initialPassword": "$(random 15)", "locale": "DE"}
]}
EOF
    echo "Wrote $MEMBERS_FILE (initial household logins; read it over SSH, never paste it into issues)"
fi
# The application container runs as uid/gid 10001 and must read the members file; aegis-agent keeps ownership.
sudo -n chgrp 10001 "$MEMBERS_FILE" && chmod 0640 "$MEMBERS_FILE"
chmod 0750 "$DEPLOY_DIR/secrets"
sudo -n chgrp 10001 "$DEPLOY_DIR/secrets"

# Persistent directories. The application container runs as uid 10001; Paperless and PostgreSQL manage their own.
sudo -n install -d -m 0750 -o 10001 -g 10001 "$DATA_DIR/ingress" "$DATA_DIR/backup-state"
sudo -n install -d -m 0750 -o 10001 -g 10001 "$EXPORTS_DIR"
sudo -n install -d -m 0750 "$DATA_DIR/postgres" "$DATA_DIR/valkey" "$DATA_DIR/caddy/data" "$DATA_DIR/caddy/config" \
    "$DATA_DIR/paperless/data" "$DATA_DIR/paperless/media" "$DATA_DIR/paperless/export" "$DATA_DIR/paperless/consume"
# Paperless runs as uid 1000 inside its container.
sudo -n chown -R 1000:1000 "$DATA_DIR/paperless"
echo "Secrets and data directories prepared."
