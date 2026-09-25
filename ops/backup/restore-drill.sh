#!/bin/sh
# Isolated local restore drill with synthetic data. Starts a disposable second stack (own Compose project name,
# own data directory, ports bound to localhost only), restores the latest Aegis export into it, verifies that
# documents are re-archived under new Paperless ids while tasks keep their source links, then tears it down.
#
# This proves the export/import path. It does NOT protect against losing the VM or the host and is not an
# external backup.
set -eu

DEPLOY_DIR="${AEGIS_DEPLOY_DIR:-/srv/aegis/deploy}"
RELEASE_DIR="${AEGIS_RELEASE_DIR:-/srv/aegis/source/current}"
DRILL_DIR="${AEGIS_DRILL_DIR:-/srv/aegis/restore-drill}"
EXPORTS_DIR="${AEGIS_EXPORTS_DIR:-/srv/aegis/exports}"
PROJECT="aegis-drill"
PORT="${AEGIS_DRILL_PORT:-18080}"
KEEP="${AEGIS_DRILL_KEEP:-no}"

[ "$(hostname)" = "aegis-dev" ] || { echo 'Refusing: expected host aegis-dev.' >&2; exit 1; }
[ -f "$DEPLOY_DIR/.env" ] || { echo "Missing $DEPLOY_DIR/.env" >&2; exit 1; }
GENERATION="${1:-$(cat "$EXPORTS_DIR/LATEST")}"
SOURCE="$EXPORTS_DIR/generations/$GENERATION"
[ -d "$SOURCE" ] || { echo "Export generation not found: $SOURCE" >&2; exit 1; }
COMMIT="$(sed -n 's/^AEGIS_COMMIT=//p' "$DEPLOY_DIR/.env")"
# The drill designates the same assistant account as the installation (ADR-0017), so that an older export's
# subject "both" is restored as the persons only.
ASSISTANTS="$(sed -n 's/^AEGIS_ASSISTANT_MEMBERS=//p' "$DEPLOY_DIR/.env" | tail -n 1)"

echo "drill: preparing isolated stack $PROJECT for generation $GENERATION"
sudo -n rm -rf "$DRILL_DIR"
mkdir -p "$DRILL_DIR/secrets" "$DRILL_DIR/postgres-init" "$DRILL_DIR/import"
chmod 0700 "$DRILL_DIR/secrets"
cp -r "$SOURCE" "$DRILL_DIR/import/$GENERATION"
cp "$RELEASE_DIR/deploy/postgres-init/01-paperless-database.sh" "$DRILL_DIR/postgres-init/"
chmod 0755 "$DRILL_DIR/postgres-init/01-paperless-database.sh"
# Provision the actual exported usernames so onboarding does not break the drill.
random() { head -c "$1" /dev/urandom | base64 | tr -d '\n=' | tr '+/' '-_'; }
python3 - "$SOURCE/data/members.json" "$DRILL_DIR/secrets/initial-members.json" <<'EOF'
import json, secrets, sys
members = json.load(open(sys.argv[1]))['members']
assert members, 'Export has no members'
with open(sys.argv[2], 'w') as out:
    json.dump({'members': [{'username': m['username'], 'displayName': m['displayName'],
                          'initialPassword': secrets.token_urlsafe(24), 'locale': m['locale'],
                          'kind': m.get('kind')}
                         for m in members]}, out)
EOF
IMPORT_ACTOR="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['members'][0]['username'])" "$SOURCE/data/members.json")"
sudo -n chgrp 10001 "$DRILL_DIR/secrets/initial-members.json" "$DRILL_DIR/secrets"
chmod 0640 "$DRILL_DIR/secrets/initial-members.json"
chmod 0750 "$DRILL_DIR/secrets"
umask 077
cat > "$DRILL_DIR/.env" <<EOF
AEGIS_COMMIT=$COMMIT
AEGIS_ASSISTANT_MEMBERS=$ASSISTANTS
AEGIS_DATA_DIR=$DRILL_DIR/data
AEGIS_EXPORTS_DIR=$DRILL_DIR/exports
AEGIS_DB_PASSWORD=$(random 24)
PAPERLESS_DB_PASSWORD=$(random 24)
PAPERLESS_SECRET_KEY=$(random 48)
PAPERLESS_ADMIN_USER=aegis-service
PAPERLESS_ADMIN_PASSWORD=$(random 24)
EOF
sudo -n install -d -m 0750 -o 10001 -g 10001 "$DRILL_DIR/data/ingress" "$DRILL_DIR/data/backup-state" "$DRILL_DIR/exports"
sudo -n install -d -m 0750 "$DRILL_DIR/data/postgres" "$DRILL_DIR/data/valkey" "$DRILL_DIR/data/caddy/data" \
    "$DRILL_DIR/data/caddy/config" "$DRILL_DIR/data/paperless/data" "$DRILL_DIR/data/paperless/media" \
    "$DRILL_DIR/data/paperless/export" "$DRILL_DIR/data/paperless/consume"
sudo -n chown -R 1000:1000 "$DRILL_DIR/data/paperless"
sudo -n chown -R 10001:10001 "$DRILL_DIR/import"

# Override: localhost-only port, import path, no Caddy.
cat > "$DRILL_DIR/compose.override.yaml" <<EOF
services:
  caddy:
    deploy:
      replicas: 0
  aegis:
    ports:
      - "127.0.0.1:$PORT:8080"
    environment:
      AEGIS_IMPORT_PATH: /srv/aegis/import/$GENERATION
      AEGIS_IMPORT_ACTOR: $IMPORT_ACTOR
    volumes:
      - $DRILL_DIR/import:/srv/aegis/import:ro
      - $DRILL_DIR/secrets/initial-members.json:/run/secrets/initial-members.json:ro
EOF
cp "$RELEASE_DIR/deploy/compose.yaml" "$DRILL_DIR/compose.yaml"
cp "$RELEASE_DIR/deploy/Caddyfile" "$DRILL_DIR/Caddyfile"
COMPOSE="sudo -n docker compose -p $PROJECT --env-file $DRILL_DIR/.env -f $DRILL_DIR/compose.yaml -f $DRILL_DIR/compose.override.yaml"

cleanup() {
    if [ "$KEEP" = "yes" ]; then
        echo "drill: keeping stack $PROJECT running on 127.0.0.1:$PORT (AEGIS_DRILL_KEEP=yes)"
        return
    fi
    echo "drill: tearing down $PROJECT"
    $COMPOSE down -v --remove-orphans >/dev/null 2>&1 || true
    sudo -n rm -rf "$DRILL_DIR"
}
trap cleanup EXIT

echo "drill: starting isolated stack (this pulls nothing new; images are reused)"
$COMPOSE up -d
echo "drill: waiting for the isolated application"
for i in $(seq 1 60); do
    if curl -fs "http://127.0.0.1:$PORT/actuator/health/readiness" >/dev/null 2>&1; then break; fi
    sleep 5
done
curl -fs "http://127.0.0.1:$PORT/actuator/health/readiness" >/dev/null || {
    echo "drill: isolated application did not become ready; last errors:" >&2
    $COMPOSE logs aegis 2>&1 | grep -E "ERROR|Caused by|Exception" | grep -vE "^\s+at " | tail -20 >&2
    exit 1
}

echo "drill: verifying restored records"
sh "$RELEASE_DIR/ops/backup/verify-drill.sh" "http://127.0.0.1:$PORT" "$DRILL_DIR/secrets/initial-members.json" "$SOURCE"
echo "drill: PASSED for generation $GENERATION (isolated local drill, not an external backup)"
