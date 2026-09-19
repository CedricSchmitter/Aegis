#!/bin/sh
# Explicit live check of automatic document classification (issue #23, ADR-0004) with synthetic documents only.
# Starts a disposable second stack (own Compose project, own data directory, application bound to localhost only),
# provisions two synthetic members with random passwords, mounts the household's Claude API key read-only, uploads a
# few generated PDFs and verifies archival, classification, review states, friendly downloads and exports with
# unchanged checksums. The stack and its data are removed afterwards. The household instance is never touched.
#
# Costs: a few Sonnet 5 calls, bounded by a lower daily limit than the household (default 0.10 USD).
# Usage (on aegis-dev, after deploying the commit): sh ops/deploy/verify-classification.sh
set -eu

DEPLOY_DIR="${AEGIS_DEPLOY_DIR:-/srv/aegis/deploy}"
RELEASE_DIR="${AEGIS_RELEASE_DIR:-/srv/aegis/source/current}"
VERIFY_DIR="${AEGIS_VERIFY_DIR:-/srv/aegis/verify-classification}"
PROJECT="aegis-verify"
PORT="${AEGIS_VERIFY_PORT:-18081}"
DAILY_LIMIT="${AEGIS_VERIFY_DAILY_LIMIT:-0.10}"
KEEP="${AEGIS_VERIFY_KEEP:-no}"

[ "$(hostname)" = "aegis-dev" ] || { echo 'Refusing: expected host aegis-dev.' >&2; exit 1; }
[ -f "$DEPLOY_DIR/.env" ] || { echo "Missing $DEPLOY_DIR/.env" >&2; exit 1; }
[ -s "$DEPLOY_DIR/secrets/ai/anthropic-api-key" ] || { echo 'No Claude API key installed; nothing to verify.' >&2; exit 1; }
COMMIT="$(sed -n 's/^AEGIS_COMMIT=//p' "$DEPLOY_DIR/.env")"

echo "verify: preparing isolated stack $PROJECT for commit $COMMIT"
sudo -n rm -rf "$VERIFY_DIR"
mkdir -p "$VERIFY_DIR/secrets" "$VERIFY_DIR/postgres-init"
chmod 0700 "$VERIFY_DIR/secrets"
cp "$RELEASE_DIR/deploy/postgres-init/01-paperless-database.sh" "$VERIFY_DIR/postgres-init/"
chmod 0755 "$VERIFY_DIR/postgres-init/01-paperless-database.sh"
random() { head -c "$1" /dev/urandom | base64 | tr -d '\n=' | tr '+/' '-_'; }
python3 - "$VERIFY_DIR/secrets/initial-members.json" <<'EOF'
import json, secrets, sys
with open(sys.argv[1], 'w') as out:
    json.dump({'members': [
        {'username': 'verify-anna', 'displayName': 'Anna Beispiel', 'initialPassword': secrets.token_urlsafe(24),
         'locale': 'DE'},
        {'username': 'verify-ben', 'displayName': 'Ben Beispiel', 'initialPassword': secrets.token_urlsafe(24),
         'locale': 'EN'}]}, out)
EOF
sudo -n chgrp 10001 "$VERIFY_DIR/secrets/initial-members.json" "$VERIFY_DIR/secrets"
chmod 0640 "$VERIFY_DIR/secrets/initial-members.json"
chmod 0750 "$VERIFY_DIR/secrets"
umask 077
cat > "$VERIFY_DIR/.env" <<EOF
AEGIS_COMMIT=$COMMIT
AEGIS_DATA_DIR=$VERIFY_DIR/data
AEGIS_EXPORTS_DIR=$VERIFY_DIR/exports
AEGIS_DB_PASSWORD=$(random 24)
PAPERLESS_DB_PASSWORD=$(random 24)
PAPERLESS_SECRET_KEY=$(random 48)
PAPERLESS_ADMIN_USER=aegis-service
PAPERLESS_ADMIN_PASSWORD=$(random 24)
AEGIS_AI_DAILY_COST_LIMIT=$DAILY_LIMIT
EOF
sudo -n install -d -m 0750 -o 10001 -g 10001 "$VERIFY_DIR/data/ingress" "$VERIFY_DIR/data/backup-state" \
    "$VERIFY_DIR/exports"
sudo -n install -d -m 0750 "$VERIFY_DIR/data/postgres" "$VERIFY_DIR/data/valkey" "$VERIFY_DIR/data/caddy/data" \
    "$VERIFY_DIR/data/caddy/config" "$VERIFY_DIR/data/paperless/data" "$VERIFY_DIR/data/paperless/media" \
    "$VERIFY_DIR/data/paperless/export" "$VERIFY_DIR/data/paperless/consume"
sudo -n chown -R 1000:1000 "$VERIFY_DIR/data/paperless"

# Override: localhost-only port, no Caddy, synthetic members, the household key read-only.
cat > "$VERIFY_DIR/compose.override.yaml" <<EOF
services:
  caddy:
    deploy:
      replicas: 0
  aegis:
    ports:
      - "127.0.0.1:$PORT:8080"
    volumes:
      - $VERIFY_DIR/secrets/initial-members.json:/run/secrets/initial-members.json:ro
      - $DEPLOY_DIR/secrets/ai:/run/secrets/ai:ro
EOF
cp "$RELEASE_DIR/deploy/compose.yaml" "$VERIFY_DIR/compose.yaml"
cp "$RELEASE_DIR/deploy/Caddyfile" "$VERIFY_DIR/Caddyfile"
mkdir -p "$VERIFY_DIR/secrets/ai"
COMPOSE="sudo -n docker compose -p $PROJECT --env-file $VERIFY_DIR/.env -f $VERIFY_DIR/compose.yaml -f $VERIFY_DIR/compose.override.yaml"

cleanup() {
    if [ "$KEEP" = "yes" ]; then
        echo "verify: keeping stack $PROJECT on 127.0.0.1:$PORT (AEGIS_VERIFY_KEEP=yes)"
        return
    fi
    echo "verify: tearing down $PROJECT"
    $COMPOSE down -v --remove-orphans >/dev/null 2>&1 || true
    sudo -n rm -rf "$VERIFY_DIR"
}
trap cleanup EXIT

echo "verify: starting isolated stack (images are reused)"
$COMPOSE up -d
for i in $(seq 1 72); do
    if curl -fs "http://127.0.0.1:$PORT/actuator/health/readiness" >/dev/null 2>&1; then break; fi
    sleep 5
done
curl -fs "http://127.0.0.1:$PORT/actuator/health/readiness" >/dev/null || {
    echo "verify: isolated application did not become ready; last errors:" >&2
    $COMPOSE logs aegis 2>&1 | grep -E "ERROR|Caused by|Exception" | grep -vE "^\s+at " | tail -20 >&2
    exit 1
}
echo "verify: running synthetic classification checks"
sudo -n python3 "$RELEASE_DIR/ops/deploy/verify-classification.py" "http://127.0.0.1:$PORT" \
    "$VERIFY_DIR/secrets/initial-members.json" "$VERIFY_DIR/exports"
