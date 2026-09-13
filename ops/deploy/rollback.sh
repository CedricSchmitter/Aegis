#!/bin/sh
# Runs on the Aegis VM: switches the application container back to a previously built commit image.
# Database migrations are additive; rolling back the application does not undo applied migrations.
set -eu
COMMIT="${1:?previously deployed commit sha required}"
DEPLOY_DIR="/srv/aegis/deploy"
[ "$(hostname)" = "aegis-dev" ] || { echo 'Refusing: expected host aegis-dev.' >&2; exit 1; }
case "$COMMIT" in *[!0-9a-f]*|'') echo 'Expected a full commit SHA.' >&2; exit 1;; esac
[ "${#COMMIT}" -eq 40 ] || { echo 'Expected a full commit SHA.' >&2; exit 1; }
sudo -n docker image inspect "aegis-app:$COMMIT" >/dev/null 2>&1 || { echo "No image aegis-app:$COMMIT" >&2; exit 1; }
sed -i "s/^AEGIS_COMMIT=.*/AEGIS_COMMIT=$COMMIT/" "$DEPLOY_DIR/.env"
cd "$DEPLOY_DIR"
if grep -q '^AEGIS_HTTPS=true$' "$DEPLOY_DIR/.env"; then
    set -- -f "$DEPLOY_DIR/compose.yaml" -f "$DEPLOY_DIR/compose.https.yaml"
else
    set -- -f "$DEPLOY_DIR/compose.yaml"
fi
sudo -n docker compose --env-file "$DEPLOY_DIR/.env" "$@" up -d --wait --wait-timeout 240 aegis
sudo -n docker compose --env-file "$DEPLOY_DIR/.env" "$@" exec -T aegis \
    wget -q -O - http://localhost:8080/api/system/version | grep -F "$COMMIT" >/dev/null
echo "$COMMIT" > "$DEPLOY_DIR/DEPLOYED_COMMIT"
