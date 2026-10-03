#!/bin/sh
# Prepares and cleans up a Claude Code cloud session (claude.ai/code) so the full backend verify can run there.
# The cloud container has Docker installed but no running daemon; Testcontainers needs one. Run it by hand in a
# session, or let the cloud environment's setup script start Docker (see README). It does nothing outside a cloud
# session (CLAUDE_CODE_REMOTE unset), so the developer PC and the VM are never touched.
#
# Usage: sh ops/cloud/session.sh start   start dockerd if needed and wait until it answers
#        sh ops/cloud/session.sh stop    remove all containers, images, volumes and build cache, then stop dockerd
set -u

[ "${CLAUDE_CODE_REMOTE:-}" = "true" ] || exit 0

STATE_DIR="${TMPDIR:-/tmp}/aegis-cloud-session"
LOG_FILE="$STATE_DIR/dockerd.log"

start() {
  if docker info >/dev/null 2>&1; then
    echo "aegis cloud session: Docker already running"
    return 0
  fi
  if ! command -v dockerd >/dev/null 2>&1; then
    echo "aegis cloud session: dockerd not installed; integration tests need ./mvnw -DskipITs verify" >&2
    return 0
  fi
  mkdir -p "$STATE_DIR"
  nohup dockerd >"$LOG_FILE" 2>&1 &
  i=0
  while [ "$i" -lt 30 ]; do
    if docker info >/dev/null 2>&1; then
      echo "aegis cloud session: Docker started (log $LOG_FILE)"
      return 0
    fi
    i=$((i + 1))
    sleep 1
  done
  echo "aegis cloud session: Docker did not answer within 30 s, see $LOG_FILE" >&2
  return 0
}

stop() {
  if docker info >/dev/null 2>&1; then
    ids="$(docker ps -aq)"
    [ -n "$ids" ] && docker rm -f $ids >/dev/null
    docker system prune -af --volumes >/dev/null 2>&1
  fi
  # The container is disposable and dockerd only ever runs for this session, whoever started it.
  for pid in $(pidof dockerd 2>/dev/null); do
    kill "$pid" 2>/dev/null
    i=0
    while kill -0 "$pid" 2>/dev/null && [ "$i" -lt 15 ]; do
      i=$((i + 1))
      sleep 1
    done
  done
  rm -rf "$STATE_DIR"
  echo "aegis cloud session: Docker cleaned up"
}

case "${1:-}" in
  start) start ;;
  stop) stop ;;
  *) echo "usage: $0 start|stop" >&2; exit 2 ;;
esac
