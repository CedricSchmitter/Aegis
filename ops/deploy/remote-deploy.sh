#!/bin/sh
# Runs on the Aegis VM: builds the application image for the given commit and starts/updates the stack.
# Deployment configuration lives in /srv/aegis/deploy (compose files copied from the release, .env kept).
set -eu

COMMIT="${1:?commit sha required}"
RELEASE_DIR="/srv/aegis/source/releases/aegis-$COMMIT"
DEPLOY_DIR="/srv/aegis/deploy"

if [ "$(hostname)" != "aegis-dev" ]; then
    echo 'Refusing: expected host aegis-dev.' >&2
    exit 1
fi
[ -f "$DEPLOY_DIR/.env" ] || { echo "Missing $DEPLOY_DIR/.env; run ops/deploy/init-secrets.sh first." >&2; exit 1; }

mkdir -p "$DEPLOY_DIR/postgres-init"
# Directory for the optional AI provider key (ADR-0003). The owner places the key file there; the application
# container (group 10001) may read it, nobody else. An empty directory means "AI not configured".
sudo -n install -d -m 0750 -o aegis-agent -g 10001 "$DEPLOY_DIR/secrets/ai"
cp "$RELEASE_DIR/deploy/compose.yaml" "$DEPLOY_DIR/compose.yaml"
cp "$RELEASE_DIR/deploy/Caddyfile" "$DEPLOY_DIR/Caddyfile"
cp "$RELEASE_DIR/deploy/Caddyfile.https" "$DEPLOY_DIR/Caddyfile.https"
cp "$RELEASE_DIR/deploy/compose.https.yaml" "$DEPLOY_DIR/compose.https.yaml"
cp "$RELEASE_DIR/deploy/postgres-init/01-paperless-database.sh" "$DEPLOY_DIR/postgres-init/01-paperless-database.sh"
chmod 0755 "$DEPLOY_DIR/postgres-init/01-paperless-database.sh"

# Pin the commit in .env so `docker compose` resolves the image tag reproducibly.
if grep -q '^AEGIS_COMMIT=' "$DEPLOY_DIR/.env"; then
    sed -i "s/^AEGIS_COMMIT=.*/AEGIS_COMMIT=$COMMIT/" "$DEPLOY_DIR/.env"
else
    echo "AEGIS_COMMIT=$COMMIT" >> "$DEPLOY_DIR/.env"
fi

cd "$DEPLOY_DIR"
export DOCKER_BUILDKIT=1
sudo -n -E docker build -f "$RELEASE_DIR/deploy/Dockerfile" --build-arg "AEGIS_COMMIT=$COMMIT" \
    -t "aegis-app:$COMMIT" "$RELEASE_DIR"
if grep -q '^AEGIS_HTTPS=true$' "$DEPLOY_DIR/.env"; then
    set -- -f "$DEPLOY_DIR/compose.yaml" -f "$DEPLOY_DIR/compose.https.yaml"
else
    set -- -f "$DEPLOY_DIR/compose.yaml"
fi
sudo -n docker compose --env-file "$DEPLOY_DIR/.env" "$@" up -d --wait --wait-timeout 240
sudo -n docker compose --env-file "$DEPLOY_DIR/.env" "$@" exec -T aegis \
    wget -q -O - http://localhost:8080/api/system/version | grep -F "$COMMIT" >/dev/null
echo "$COMMIT" > "$DEPLOY_DIR/DEPLOYED_COMMIT"
sudo -n docker compose --env-file "$DEPLOY_DIR/.env" -f "$DEPLOY_DIR/compose.yaml" ps
