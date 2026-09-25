"""Hermetic backup regression tests; no mounts, service changes, or real Restic repository."""
import fcntl
import json
from pathlib import Path
import subprocess
import tempfile
import types
import unittest
from unittest.mock import patch
import backup


class BackupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.mount = self.root / "disk"
        self.repo = self.mount / "restic"
        self.repo.mkdir(parents=True)
        (self.mount / ".aegis-backup-destination").touch()
        (self.repo / "config").touch()
        (self.root / "password").touch()
        (self.root / "DEPLOYED_COMMIT").write_text("verified-commit")
        self.state = self.root / "data/backup-state/state.json"
        self.env = dict(AEGIS_BACKUP_MOUNTPOINT=str(self.mount), RESTIC_REPOSITORY=str(self.repo),
                        RESTIC_PASSWORD_FILE=str(self.root / "password"), AEGIS_DEPLOY_DIR=str(self.root),
                        AEGIS_DATA_DIR=str(self.root / "data"), AEGIS_EXPORTS_DIR=str(self.root / "exports"),
                        AEGIS_BACKUP_STAGING=str(self.root / "staging"))
        self.calls = []
        self.fail = None
        self.state_at_stop = None
        mounted = patch.object(Path, "is_mount", return_value=True)
        mounted.start()
        self.addCleanup(mounted.stop)
        usage = patch.object(backup.shutil, "disk_usage",
                             return_value=types.SimpleNamespace(total=1000, used=250, free=750))
        usage.start()
        self.addCleanup(usage.stop)

    def fake_run(self, args, **kwargs):
        self.calls.append(args)
        if "stop" in args:
            self.state_at_stop = self.read_state()
        if self.fail is not None and self.fail(args):
            raise subprocess.CalledProcessError(1, args)
        return types.SimpleNamespace(stdout="aegis\npaperless\nbroker\n")

    def read_state(self):
        return json.loads(self.state.read_text())

    def run_backup(self):
        with patch.object(backup, "run", self.fake_run):
            backup.backup(self.env)

    def assert_fails_with(self, code):
        with patch.object(backup, "run", self.fake_run):
            with self.assertRaises(backup.BackupFailure) as raised:
                backup.backup(self.env)
        self.assertEqual(raised.exception.code, code)
        state = self.read_state()
        self.assertEqual(state["failureCode"], code)
        self.assertIsNone(state["runningSince"])
        self.assertIsNone(state["error"])
        self.assertEqual(state["schemaVersion"], 2)
        return state

    def test_unmounted_destination_never_calls_services_or_restic(self):
        with patch.object(Path, "is_mount", return_value=False), patch.object(backup, "run") as run:
            with self.assertRaises(backup.BackupFailure) as raised:
                backup.backup(self.env)
            run.assert_not_called()
        self.assertEqual(raised.exception.code, "destination.notMounted")
        state = self.read_state()
        self.assertEqual(state["failureCode"], "destination.notMounted")
        self.assertFalse(state["destinationVerified"])
        self.assertNotIn("backupDisk", state, "an unmounted disk cannot be measured")

    def test_each_destination_problem_has_its_own_code(self):
        (self.root / "password").unlink()
        self.assert_fails_with("password.missing")
        (self.repo / "config").unlink()
        self.assert_fails_with("repository.notInitialized")
        (self.mount / ".aegis-backup-destination").unlink()
        self.assert_fails_with("destination.markerMissing")
        self.assertEqual(self.calls, [])

    def test_a_disk_that_cannot_be_read_is_named_as_such(self):
        with patch.object(Path, "is_file", side_effect=OSError(5, "Input/output error")):
            state = self.assert_fails_with("destination.unreadable")
        self.assertEqual(self.calls, [])
        self.assertEqual(state["backupDisk"]["freeBytes"], 750, "a mounted but unreadable disk is still measured")

    def test_symlink_and_parent_traversal_cannot_escape_destination(self):
        outside = self.root / "other"
        outside.mkdir()
        (outside / "config").touch()
        (self.mount / "link").symlink_to(outside, target_is_directory=True)
        for path in (self.mount / "link", self.mount / ".." / "other"):
            with self.assertRaises(backup.BackupFailure) as raised:
                backup.destination(dict(self.env, RESTIC_REPOSITORY=str(path)))
            self.assertEqual(raised.exception.code, "repository.outsideMount")

    def test_writers_are_stopped_for_dumps_and_backup_then_restarted(self):
        self.run_backup()
        stop = next(i for i, args in enumerate(self.calls) if "stop" in args)
        dump = next(i for i, args in enumerate(self.calls) if "pg_dump" in args)
        snapshot = next(i for i, args in enumerate(self.calls) if args[:2] == ["restic", "backup"])
        start = next(i for i, args in enumerate(self.calls) if "start" in args)
        self.assertLess(stop, dump)
        self.assertLess(dump, snapshot)
        self.assertLess(snapshot, start)
        self.assertFalse(any("init" in args or "forget" in args for args in self.calls))

    def test_a_running_backup_is_marked_before_services_stop_and_cleared_on_success(self):
        backup.write_state(self.state, error="Backup failed: ValueError", failureCode="check.failed")
        self.run_backup()
        self.assertIsNotNone(self.state_at_stop["runningSince"])
        state = self.read_state()
        self.assertIsNone(state["runningSince"])
        self.assertIsNone(state["failureCode"])
        self.assertIsNone(state["error"], "the legacy free-text error must not survive a success")
        self.assertEqual(state["lastSuccessAt"], state["lastAttemptAt"])
        self.assertTrue(state["destinationVerified"])
        self.assertEqual(state["backupDisk"]["totalBytes"], 1000)
        self.assertEqual(state["backupDisk"]["freeBytes"], 750)
        self.assertEqual(state["backupDisk"]["measuredAt"], state["lastAttemptAt"])

    def test_failure_restarts_writers_and_preserves_last_success(self):
        backup.write_state(self.state, lastSuccessAt="previous-success")
        self.fail = lambda args: args[:2] == ["restic", "backup"]
        state = self.assert_fails_with("capture.failed")
        self.assertTrue(any("start" in args for args in self.calls))
        self.assertEqual(state["lastSuccessAt"], "previous-success")
        self.assertEqual(state["backupDisk"]["freeBytes"], 750, "a failed run still measures the mounted disk")

    def test_unreadable_repository_failed_check_and_failed_restart_are_distinguished(self):
        self.fail = lambda args: args[:2] == ["restic", "snapshots"]
        self.assert_fails_with("repository.unreadable")
        self.assertFalse(any("stop" in args for args in self.calls))

        self.fail = lambda args: args[:2] == ["restic", "check"]
        self.assert_fails_with("check.failed")

        self.fail = lambda args: "start" in args
        self.assert_fails_with("services.restartFailed")

    def test_a_second_run_leaves_the_running_backups_state_alone(self):
        backup.write_state(self.state, runningSince="first-run", lastAttemptAt="first-run")
        staging = self.root / "staging"
        staging.mkdir()
        with (staging / "backup.lock").open("w") as held:
            fcntl.flock(held, fcntl.LOCK_EX)
            with patch.object(backup, "run") as run, self.assertRaises(backup.BackupAlreadyRunning):
                backup.backup(self.env)
            run.assert_not_called()
        self.assertEqual(self.read_state(), {"runningSince": "first-run", "lastAttemptAt": "first-run"})


if __name__ == "__main__":
    unittest.main()
