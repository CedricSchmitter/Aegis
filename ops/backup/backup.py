"""Coordinated recovery artifacts for an explicitly initialized external Restic repository.

The state file (read by the application's status page, ADR-0015) records the outcome with a stable failure code,
never command output: `runningSince` while a run is active, `failureCode` after a failed attempt, `lastSuccessAt`
after a verified run and `backupDisk` with the capacity of the mounted backup disk after every attempt.
"""
import datetime
import fcntl
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile

STATE_SCHEMA_VERSION = 2


class BackupFailure(Exception):
    """A failed backup attempt with a stable, machine-readable code; the message is the code only."""

    def __init__(self, code):
        super().__init__(code)
        self.code = code


class BackupAlreadyRunning(Exception):
    """Another backup holds the lock; the state file belongs to that run and stays untouched."""


def run(args, **kwargs):
    # Never print command output: service commands may include sensitive diagnostics.
    return subprocess.run(args, check=True, stderr=subprocess.PIPE, **kwargs)


def step(code, action, *args, **kwargs):
    """Runs one phase of the backup and turns any failure into a BackupFailure with the phase's code."""
    try:
        return action(*args, **kwargs)
    except BackupFailure:
        raise
    except Exception as failure:
        raise BackupFailure(code) from failure


def destination(env):
    mount = Path(env.get("AEGIS_BACKUP_MOUNTPOINT", "")).resolve()
    if not env.get("AEGIS_BACKUP_MOUNTPOINT") or not mount.is_mount() or mount == Path("/"):
        raise BackupFailure("destination.notMounted")
    if not (mount / ".aegis-backup-destination").is_file():
        raise BackupFailure("destination.markerMissing")
    repository = Path(env.get("RESTIC_REPOSITORY", "")).resolve()
    if repository == mount or not repository.is_relative_to(mount):
        raise BackupFailure("repository.outsideMount")
    if not (repository / "config").is_file():
        # Initialize the dedicated Restic repository explicitly before the first backup.
        raise BackupFailure("repository.notInitialized")
    password = Path(env.get("RESTIC_PASSWORD_FILE", ""))
    if not password.is_file():
        raise BackupFailure("password.missing")
    return mount, repository


def write_state(path, **updates):
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        state = json.loads(path.read_text())
    except (OSError, ValueError):
        state = {}
    state.update(updates)
    with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, delete=False) as out:
        json.dump(state, out)
        temp = Path(out.name)
    temp.chmod(0o644)
    temp.replace(path)


def disk_capacity(env, now):
    """Total and free bytes of the backup disk, or None when it is not mounted."""
    mount = env.get("AEGIS_BACKUP_MOUNTPOINT")
    if not mount or not Path(mount).is_mount():
        return None
    try:
        usage = shutil.disk_usage(mount)
    except OSError:
        return None
    return {"totalBytes": usage.total, "freeBytes": usage.free, "measuredAt": now}


def outcome(env, now, **fields):
    """State fields every finished attempt writes; the legacy free-text `error` is always cleared."""
    fields.update(schemaVersion=STATE_SCHEMA_VERSION, lastAttemptAt=now, runningSince=None, error=None)
    capacity = disk_capacity(env, now)
    if capacity is not None:
        fields["backupDisk"] = capacity
    return fields


def backup(env):
    deploy = Path(env.get("AEGIS_DEPLOY_DIR", "/srv/aegis/deploy"))
    data = Path(env.get("AEGIS_DATA_DIR", "/srv/aegis/data"))
    exports = Path(env.get("AEGIS_EXPORTS_DIR", "/srv/aegis/exports"))
    staging = Path(env.get("AEGIS_BACKUP_STAGING", "/srv/aegis/backup-staging"))
    state = Path(env.get("AEGIS_BACKUP_STATE_FILE", str(data / "backup-state/state.json")))
    now = datetime.datetime.now(datetime.timezone.utc).isoformat()
    compose = ["docker", "compose", "--env-file", str(deploy / ".env"), "-f", str(deploy / "compose.yaml")]
    staging.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (staging / "backup.lock").open("w") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as busy:
            raise BackupAlreadyRunning() from busy
        verified = False
        try:
            mount, repository = destination(env)
            verified = True
            restic_env = dict(env, RESTIC_REPOSITORY=str(repository))
            step("repository.unreadable", run, ["restic", "snapshots", "--json"], env=restic_env,
                 stdout=subprocess.DEVNULL)
            running = step("capture.failed", run, compose + ["ps", "--services", "--status", "running"],
                           stdout=subprocess.PIPE, text=True).stdout.splitlines()
            writers = [name for name in ("aegis", "paperless", "broker") if name in running]
            write_state(state, schemaVersion=STATE_SCHEMA_VERSION, destinationVerified=True, lastAttemptAt=now,
                        runningSince=now, error=None)
            try:
                if writers:
                    step("capture.failed", run, compose + ["stop", "--timeout", "120", *writers],
                         stdout=subprocess.DEVNULL)
                step("capture.failed", capture, env, compose, deploy, data, exports, staging, now, writers,
                     (mount, repository), restic_env)
            finally:
                if writers:
                    step("services.restartFailed", run, compose + ["start", *reversed(writers)],
                         stdout=subprocess.DEVNULL)
            step("check.failed", run, ["restic", "check", "--read-data-subset=5%"], env=restic_env,
                 stdout=subprocess.DEVNULL)
            write_state(state, **outcome(env, now, destinationVerified=True, lastSuccessAt=now,
                                         repository=str(repository), failureCode=None))
        except Exception as failure:
            code = failure.code if isinstance(failure, BackupFailure) else "unexpected"
            write_state(state, **outcome(env, now, destinationVerified=verified, failureCode=code))
            raise


def capture(env, compose, deploy, data, exports, staging, now, writers, target, restic_env):
    with tempfile.TemporaryDirectory(prefix="generation-", dir=staging) as folder:
        generation = Path(folder)
        for database in ("aegis", "paperless"):
            with (generation / (database + ".dump")).open("wb") as dump:
                run(compose + ["exec", "-T", "postgres", "pg_dump", "-U", "aegis",
                               "-d", database, "--no-owner", "-Fc"], stdout=dump)
        (generation / "recovery.json").write_text(json.dumps({
            "schemaVersion": 1, "createdAt": now,
            "commit": (deploy / "DEPLOYED_COMMIT").read_text().strip(),
            "writersStopped": writers,
        }))
        if destination(env) != target:
            raise BackupFailure("destination.changed")
        run(["restic", "backup", "--tag", "aegis", str(generation), str(data / "ingress"),
             str(data / "paperless"), str(data / "valkey"), str(data / "caddy"),
             str(exports), str(deploy)], env=restic_env, stdout=subprocess.DEVNULL)


if __name__ == "__main__":
    if socket.gethostname() != "aegis-dev" or os.geteuid() != 0:
        raise SystemExit("Run as root only inside aegis-dev")
    try:
        backup(os.environ)
    except BackupAlreadyRunning:
        raise SystemExit("Another backup is running; its state file was left untouched")
    except BackupFailure as failure:
        raise SystemExit("Backup failed (" + failure.code + "); inspect the status page and service health")
    except Exception:
        raise SystemExit("Backup failed; inspect the status file and service health")
    print("Backup completed; external restore verification remains a separate step")
