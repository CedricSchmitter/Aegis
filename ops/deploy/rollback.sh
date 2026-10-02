#!/bin/sh
# Runs on the Aegis VM: switches the stack back to a previously built commit image and brings every service of the
# stack back to running. A failed deployment can leave a dependent service such as Caddy in state `Created` (it
# waits for a healthy application), so starting only the application is not enough.
# Database migrations are additive; rolling back the application does not undo applied migrations.
set -eu
COMMIT="${1:?previously deployed commit sha required}"
DEPLOY_DIR="${AEGIS_DEPLOY_DIR:-/srv/aegis/deploy}"
RELEASE_DIR="${AEGIS_RELEASES_DIR:-/srv/aegis/source/releases}/aegis-$COMMIT"
[ "$(hostname)" = "aegis-dev" ] || { echo 'Refusing: expected host aegis-dev.' >&2; exit 1; }
case "$COMMIT" in *[!0-9a-f]*|'') echo 'Expected a full commit SHA.' >&2; exit 1;; esac
[ "${#COMMIT}" -eq 40 ] || { echo 'Expected a full commit SHA.' >&2; exit 1; }
sudo -n docker image inspect "aegis-app:$COMMIT" >/dev/null 2>&1 || { echo "No image aegis-app:$COMMIT" >&2; exit 1; }

# Same rule as remote-deploy.sh: the last successfully deployed commit other than this one stays available as
# PREVIOUS_COMMIT, whose image prune-docker.sh keeps.
PREVIOUS="$(cat "$DEPLOY_DIR/DEPLOYED_COMMIT" 2>/dev/null || true)"
if [ "$PREVIOUS" = "$COMMIT" ]; then
    PREVIOUS="$(cat "$DEPLOY_DIR/PREVIOUS_COMMIT" 2>/dev/null || true)"
fi

# Starting the whole stack uses the compose files, so restore the ones of the target release; a failed deployment
# has already replaced them with its own.
if [ -f "$RELEASE_DIR/deploy/compose.yaml" ]; then
    cp "$RELEASE_DIR/deploy/compose.yaml" "$DEPLOY_DIR/compose.yaml"
    cp "$RELEASE_DIR/deploy/Caddyfile" "$DEPLOY_DIR/Caddyfile"
    cp "$RELEASE_DIR/deploy/Caddyfile.https" "$DEPLOY_DIR/Caddyfile.https"
    cp "$RELEASE_DIR/deploy/compose.https.yaml" "$DEPLOY_DIR/compose.https.yaml"
    cp "$RELEASE_DIR/deploy/postgres-init/01-paperless-database.sh" "$DEPLOY_DIR/postgres-init/01-paperless-database.sh"
    chmod 0755 "$DEPLOY_DIR/postgres-init/01-paperless-database.sh"
else
    echo "Release $RELEASE_DIR not found; keeping the current compose files." >&2
fi

sed -i "s/^AEGIS_COMMIT=.*/AEGIS_COMMIT=$COMMIT/" "$DEPLOY_DIR/.env"
cd "$DEPLOY_DIR"
if grep -q '^AEGIS_HTTPS=true$' "$DEPLOY_DIR/.env"; then
    set -- -f "$DEPLOY_DIR/compose.yaml" -f "$DEPLOY_DIR/compose.https.yaml"
else
    set -- -f "$DEPLOY_DIR/compose.yaml"
fi
# No service argument: every service must be running (healthy where it has a health check), including Caddy.
sudo -n docker compose --env-file "$DEPLOY_DIR/.env" "$@" up -d --wait --wait-timeout 240
sudo -n docker compose --env-file "$DEPLOY_DIR/.env" "$@" exec -T aegis \
    wget -q -O - http://localhost:8080/api/system/version | grep -F "$COMMIT" >/dev/null
echo "$COMMIT" > "$DEPLOY_DIR/DEPLOYED_COMMIT"
[ -z "$PREVIOUS" ] || echo "$PREVIOUS" > "$DEPLOY_DIR/PREVIOUS_COMMIT"
sudo -n docker compose --env-file "$DEPLOY_DIR/.env" "$@" ps
