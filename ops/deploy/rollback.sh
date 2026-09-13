#!/bin/sh
# Runs on the Aegis VM: switches the application container back to a previously built commit image.
# Database migrations are additive; rolling back the application does not undo applied migrations.
set -eu
COMMIT="${1:?previously deployed commit sha required}"
DEPLOY_DIR="/srv/aegis/deploy"
sudo -n docker image inspect "aegis-app:$COMMIT" >/dev/null 2>&1 || { echo "No image aegis-app:$COMMIT" >&2; exit 1; }
sed -i "s/^AEGIS_COMMIT=.*/AEGIS_COMMIT=$COMMIT/" "$DEPLOY_DIR/.env"
echo "$COMMIT" > "$DEPLOY_DIR/DEPLOYED_COMMIT"
cd "$DEPLOY_DIR"
sudo -n docker compose --env-file "$DEPLOY_DIR/.env" -f "$DEPLOY_DIR/compose.yaml" up -d aegis
