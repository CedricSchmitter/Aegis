"""Hermetic backup regression tests; no mounts, service changes, or real Restic repository."""
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
        self.env = dict(AEGIS_BACKUP_MOUNTPOINT=str(self.mount), RESTIC_REPOSITORY=str(self.repo),
                        RESTIC_PASSWORD_FILE=str(self.root / "password"), AEGIS_DEPLOY_DIR=str(self.root),
                        AEGIS_DATA_DIR=str(self.root / "data"), AEGIS_EXPORTS_DIR=str(self.root / "exports"),
                        AEGIS_BACKUP_STAGING=str(self.root / "staging"))
        self.calls = []

    def fake_run(self, args, **kwargs):
        self.calls.append(args)
        if args[:2] == ["restic", "backup"] and getattr(self, "fail_backup", False):
            raise subprocess.CalledProcessError(1, args)
        return types.SimpleNamespace(stdout="aegis\npaperless\nbroker\n")

    def test_unmounted_destination_never_calls_services_or_restic(self):
        with patch.object(Path, "is_mount", return_value=False), patch.object(backup, "run") as run:
            with self.assertRaises(ValueError):
                backup.backup(self.env)
            run.assert_not_called()

    def test_symlink_and_parent_traversal_cannot_escape_destination(self):
        outside = self.root / "other"
        outside.mkdir()
        (outside / "config").touch()
        (self.mount / "link").symlink_to(outside, target_is_directory=True)
        with patch.object(Path, "is_mount", return_value=True):
            for path in (self.mount / "link", self.mount / ".." / "other"):
                with self.assertRaises(ValueError):
                    backup.destination(dict(self.env, RESTIC_REPOSITORY=str(path)))

    def test_writers_are_stopped_for_dumps_and_backup_then_restarted(self):
        with patch.object(Path, "is_mount", return_value=True), patch.object(backup, "run", self.fake_run):
            backup.backup(self.env)
        stop = next(i for i, args in enumerate(self.calls) if "stop" in args)
        dump = next(i for i, args in enumerate(self.calls) if "pg_dump" in args)
        snapshot = next(i for i, args in enumerate(self.calls) if args[:2] == ["restic", "backup"])
        start = next(i for i, args in enumerate(self.calls) if "start" in args)
        self.assertLess(stop, dump)
        self.assertLess(dump, snapshot)
        self.assertLess(snapshot, start)
        self.assertFalse(any("init" in args or "forget" in args for args in self.calls))

    def test_failure_restarts_writers_and_preserves_last_success(self):
        state = self.root / "data/backup-state/state.json"
        backup.write_state(state, lastSuccessAt="previous-success")
        self.fail_backup = True
        with patch.object(Path, "is_mount", return_value=True), patch.object(backup, "run", self.fake_run):
            with self.assertRaises(subprocess.CalledProcessError):
                backup.backup(self.env)
        self.assertTrue(any("start" in args for args in self.calls))
        result = json.loads(state.read_text())
        self.assertEqual(result["lastSuccessAt"], "previous-success")
        self.assertIn("failed", result["error"])


if __name__ == "__main__":
    unittest.main()
