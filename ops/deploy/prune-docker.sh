#!/bin/sh
# Runs on the Aegis VM after a successful deployment: bounds the disk space that repeated image builds leave
# behind. The VM disk is thin-provisioned on a Proxmox pool smaller than its virtual size, so unbounded build
# cache and old images can fill the pool and freeze the VM (2026-09-26).
#
# Keeps: the build cache up to AEGIS_BUILD_CACHE_KEEP (least recently used records go first, so the Maven and npm
# cache mounts of the last build stay), the current and the previous aegis-app image (rollback.sh), and every
# image a container still references. Removes: older aegis-app tags and dangling images. Then returns the freed
# blocks to the thin pool with fstrim when the disk supports discard.
#
# Every step is attempted; the exit status is non-zero when any step failed, so the caller can report it.
# Usage: prune-docker.sh <current-commit> [previous-commit]
set -u

CURRENT="${1:?current commit sha required}"
PREVIOUS="${2:-}"
CACHE_KEEP="${AEGIS_BUILD_CACHE_KEEP:-3GB}"
DOCKER_ROOT="${AEGIS_DOCKER_ROOT:-/var/lib/docker}"
status=0

warn() {
    echo "Cleanup warning: $*" >&2
    status=1
}

if containers="$(sudo -n docker ps -a --format '{{.Image}}')" \
        && tags="$(sudo -n docker image ls aegis-app --format '{{.Tag}}')"; then
    for tag in $tags; do
        case "$tag" in
            "$CURRENT"|"$PREVIOUS"|'<none>') continue ;;
        esac
        if printf '%s\n' "$containers" | grep -qxF "aegis-app:$tag"; then
            echo "Keeping aegis-app:$tag (referenced by a container)"
            continue
        fi
        sudo -n docker image rm "aegis-app:$tag" >/dev/null || warn "could not remove aegis-app:$tag"
    done
else
    warn "could not list containers or images; old aegis-app images were kept"
fi

sudo -n docker image prune -f >/dev/null || warn "removing dangling images failed"
sudo -n docker builder prune -f --keep-storage "$CACHE_KEEP" >/dev/null || warn "pruning the build cache failed"

# fstrim.timer trims weekly; trimming now hands the space of this prune back to the thin pool before the next
# builds allocate fresh blocks. ext4 skips groups trimmed since mount, so repeating it is cheap.
if mountpoint="$(findmnt -no TARGET --target "$DOCKER_ROOT")" \
        && device="$(findmnt -no SOURCE --target "$DOCKER_ROOT")"; then
    discard_max="$(lsblk -dno DISC-MAX "$device" 2>/dev/null | tr -d ' ')"
    case "$discard_max" in
        ''|0|0B)
            echo "Skipping fstrim: $device does not support discard, so freed space stays allocated in the pool." ;;
        *)
            sudo -n fstrim -v "$mountpoint" || warn "fstrim $mountpoint failed" ;;
    esac
else
    warn "could not find the file system of $DOCKER_ROOT; fstrim skipped"
fi

sudo -n docker system df || warn "docker system df failed"
exit "$status"
