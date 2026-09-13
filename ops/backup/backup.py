"""Coordinated recovery artifacts for an explicitly initialized external Restic repository."""
import datetime
import fcntl
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile


def run(args, **kwargs):
    # Never print command output: service commands may include sensitive diagnostics.
    return subprocess.run(args, check=True, stderr=subprocess.PIPE, **kwargs)


def destination(env):
    mount = Path(env.get("AEGIS_BACKUP_MOUNTPOINT", "")).resolve()
    if not env.get("AEGIS_BACKUP_MOUNTPOINT") or not mount.is_mount() or mount == Path("/"):
        raise ValueError("External destination is not mounted")
    if not (mount / ".aegis-backup-destination").is_file():
        raise ValueError("External destination marker missing")
    repository = Path(env.get("RESTIC_REPOSITORY", "")).resolve()
    if repository == mount or not repository.is_relative_to(mount):
        raise ValueError("Restic repository escapes the external mount")
    if not (repository / "config").is_file():
        raise ValueError("Initialize the dedicated Restic repository explicitly before backup")
    password = Path(env.get("RESTIC_PASSWORD_FILE", ""))
    if not password.is_file():
        raise ValueError("Restic password file missing")
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


def backup(env):
    deploy = Path(env.get("AEGIS_DEPLOY_DIR", "/srv/aegis/deploy"))
    data = Path(env.get("AEGIS_DATA_DIR", "/srv/aegis/data"))
    exports = Path(env.get("AEGIS_EXPORTS_DIR", "/srv/aegis/exports"))
    staging = Path(env.get("AEGIS_BACKUP_STAGING", "/srv/aegis/backup-staging"))
    state = Path(env.get("AEGIS_BACKUP_STATE_FILE", str(data / "backup-state/state.json")))
    now = datetime.datetime.now(datetime.timezone.utc).isoformat()
    verified = False
    compose = ["docker", "compose", "--env-file", str(deploy / ".env"), "-f", str(deploy / "compose.yaml")]
    try:
        mount, repository = destination(env)
        verified = True
        restic_env = dict(env, RESTIC_REPOSITORY=str(repository))
        run(["restic", "snapshots", "--json"], env=restic_env, stdout=subprocess.DEVNULL)
        staging.mkdir(parents=True, exist_ok=True, mode=0o700)
        with (staging / "backup.lock").open("w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            running = run(compose + ["ps", "--services", "--status", "running"],
                          stdout=subprocess.PIPE, text=True).stdout.splitlines()
            writers = [name for name in ("aegis", "paperless", "broker") if name in running]
            write_state(state, destinationVerified=True, lastAttemptAt=now, error="Backup in progress")
            try:
                if writers:
                    run(compose + ["stop", "--timeout", "120", *writers], stdout=subprocess.DEVNULL)
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
                    if destination(env) != (mount, repository):
                        raise ValueError("Backup destination changed during capture")
                    run(["restic", "backup", "--tag", "aegis", str(generation), str(data / "ingress"),
                         str(data / "paperless"), str(data / "valkey"), str(data / "caddy"),
                         str(exports), str(deploy)], env=restic_env, stdout=subprocess.DEVNULL)
            finally:
                if writers:
                    run(compose + ["start", *reversed(writers)], stdout=subprocess.DEVNULL)
            run(["restic", "check", "--read-data-subset=5%"], env=restic_env, stdout=subprocess.DEVNULL)
            write_state(state, destinationVerified=True, lastSuccessAt=now, lastAttemptAt=now,
                        repository=str(repository), error=None)
    except Exception as failure:
        write_state(state, destinationVerified=verified, lastAttemptAt=now,
                    error="Backup failed: " + type(failure).__name__)
        raise


if __name__ == "__main__":
    if socket.gethostname() != "aegis-dev" or os.geteuid() != 0:
        raise SystemExit("Run as root only inside aegis-dev")
    try:
        backup(os.environ)
    except Exception:
        raise SystemExit("Backup failed; inspect the status file and service health")
    print("Backup completed; external restore verification remains a separate step")
