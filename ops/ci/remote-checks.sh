#!/bin/sh
# Runs the CI jobs of .github/workflows (backend.yml, frontend.yml, smoke.yml) on the Aegis VM, for times when GitHub
# Actions is unavailable. Called by ops/ci/vm-checks.sh with the checked commit extracted to <run-dir>/src and this
# tooling in <run-dir>/ci, so a commit that predates these scripts can be checked too.
#
# It never touches the household stack in /srv/aegis/deploy: the browser smoke test starts its own compose project
# (aegis-ci) with generated synthetic credentials, its own data directory, no published port and an empty directory
# instead of the Claude runner socket, and removes it again. Only synthetic test material is used. The frontend and
# the browser test share one image (Playwright, which ships the Node version CI uses), so only that image and the
# application's base images stay on the VM; the CI image and its build cache are removed after each run.
#
# Usage: sh remote-checks.sh <run-dir> [backend] [frontend] [smoke]   (default: all three)
set -u

RUN="$1"
shift
JOBS="${*:-backend frontend smoke}"
SRC="$RUN/src"
PROJECT="aegis-ci"
PLAYWRIGHT_VERSION="$(sed -n '/"node_modules\/@playwright\/test": {/{n;s/.*"version": "\([^"]*\)".*/\1/p;}' "$SRC/e2e/package-lock.json")"
PLAYWRIGHT_IMAGE="mcr.microsoft.com/playwright:v$PLAYWRIGHT_VERSION-noble"
SUMMARY=""
FAILED=0

record() {
  SUMMARY="$SUMMARY
$1: $2"
  [ "$2" = "PASS" ] || FAILED=$((FAILED + 1))
  echo "=== $1: $2 $(date -Is)"
}

backend() {
  (cd "$SRC/backend" && sudo -n env HOME=/root TESTCONTAINERS_RYUK_DISABLED=true ./mvnw -B -ntp verify)
}

frontend() {
  sudo -n docker run --rm -v "$SRC:/src" -w /src/frontend "$PLAYWRIGHT_IMAGE" sh -ec '
    node --version
    npm ci --no-audit --no-fund
    cp src/api/schema.d.ts /tmp/schema.committed.d.ts
    npm run generate:api
    if ! cmp -s src/api/schema.d.ts /tmp/schema.committed.d.ts; then
      echo "The generated API client differs from api/openapi.json; run npm run generate:api and commit it."
      diff -u /tmp/schema.committed.d.ts src/api/schema.d.ts | head -60
      exit 1
    fi
    npm run check
    npm run build'
}

compose() {
  sudo -n docker compose -p "$PROJECT" --env-file "$SRC/deploy/.env" -f "$SRC/deploy/compose.yaml" \
    -f "$SRC/deploy/compose.ci.yaml" -f "$RUN/ci/compose.vm-ci.yaml" "$@"
}

ready() {
  for _ in $(seq 1 72); do
    if compose exec -T caddy wget -q -O /dev/null http://localhost/actuator/health/readiness 2>/dev/null; then
      return 0
    fi
    sleep 5
  done
  return 1
}

# The browser runs in the Playwright image inside the proxy's network namespace, so http://localhost is the CI stack,
# exactly as in smoke.yml, and service workers still see a secure context.
playwright() {
  sudo -n docker run --rm --ipc=host --network "container:$PROJECT-caddy-1" -v "$SRC:/src" -w /src/e2e \
    -e AEGIS_BASE_URL=http://localhost -e AEGIS_USER_A=anna -e AEGIS_USER_B=ben \
    -e AEGIS_PASSWORD_A="$PW_A" -e AEGIS_PASSWORD_B="$PW_B" -e CI=true \
    "$PLAYWRIGHT_IMAGE" sh -ec "npm ci --no-audit --no-fund >/dev/null && npx playwright test $*"
}

smoke() {
  DATA="$RUN/data"
  EXPORTS="$RUN/exports"
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  mkdir -p "$DATA/ingress" "$DATA/backup-state" "$EXPORTS" "$RUN/no-claude-runner" "$SRC/deploy/secrets/ai"
  for d in postgres valkey caddy/data caddy/config paperless/data paperless/media paperless/export paperless/consume; do
    mkdir -p "$DATA/$d"
  done
  sudo -n chown -R 10001:10001 "$DATA/ingress" "$DATA/backup-state" "$EXPORTS"
  sudo -n chown -R 1000:1000 "$DATA/paperless"
  PW_A="ci-anna-$(openssl rand -hex 8)"
  PW_B="ci-ben-$(openssl rand -hex 8)"
  cat > "$SRC/deploy/secrets/initial-members.json" <<EOF
{"members": [
  {"username": "anna", "displayName": "Anna", "initialPassword": "$PW_A", "locale": "DE"},
  {"username": "ben", "displayName": "Ben", "initialPassword": "$PW_B", "locale": "DE"}
]}
EOF
  cat > "$SRC/deploy/.env" <<EOF
AEGIS_COMMIT=ci
AEGIS_DATA_DIR=$DATA
AEGIS_EXPORTS_DIR=$EXPORTS
AEGIS_CLAUDE_RUNNER_DIR=$RUN/no-claude-runner
AEGIS_DB_PASSWORD=$(openssl rand -hex 16)
PAPERLESS_DB_PASSWORD=$(openssl rand -hex 16)
PAPERLESS_SECRET_KEY=$(openssl rand -hex 32)
PAPERLESS_ADMIN_USER=aegis-service
PAPERLESS_ADMIN_PASSWORD=$(openssl rand -hex 16)
EOF
  status=0
  sudo -n docker build -q -f "$SRC/deploy/Dockerfile" --build-arg AEGIS_COMMIT=ci -t aegis-app:ci "$SRC" >/dev/null \
    && compose up -d --no-build \
    && ready \
    && playwright \
    && compose restart aegis caddy \
    && ready \
    && playwright --grep "'persist after restart'" \
    || status=1
  if [ "$status" -ne 0 ]; then
    compose logs --tail 200
  fi
  compose down -v --remove-orphans
  sudo -n docker image rm aegis-app:ci >/dev/null 2>&1 || true
  # The CI build leaves several GB of build cache on a thin-provisioned disk; nothing else needs it afterwards.
  sudo -n docker builder prune -f >/dev/null 2>&1 || true
  return "$status"
}

for job in $JOBS; do
  echo "=== $job: start $(date -Is)"
  case "$job" in
    backend | frontend | smoke)
      if "$job"; then record "$job" PASS; else record "$job" FAIL; fi
      ;;
    *) record "$job" "UNKNOWN JOB" ;;
  esac
done

echo "=== Summary for $(basename "$RUN") ($PROJECT, Playwright $PLAYWRIGHT_VERSION):$SUMMARY"
if [ "$FAILED" -eq 0 ]; then
  sudo -n rm -rf "$RUN"
else
  echo "=== Kept $RUN for inspection (reports under src/**/target and src/e2e/test-results); remove it with sudo when done."
fi
exit "$FAILED"
