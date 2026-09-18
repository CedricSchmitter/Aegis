"""Credential installation tests use synthetic keys and temporary directories only."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "key_installer", Path(__file__).with_name("set-anthropic-key.py"))
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class KeyInstallationTest(unittest.TestCase):
    def test_invalid_input_preserves_existing_key(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "anthropic-api-key"
            target.write_text("previous")
            for invalid in ("", "oauth-token", "sk-ant-two lines", "sk-ant-a\nb"):
                with self.assertRaises(SystemExit):
                    installer.install_key(Path(directory), invalid)
                self.assertEqual(target.read_text(), "previous")
            self.assertEqual(list(Path(directory).iterdir()), [target])

    def test_atomic_replacement_sets_permissions_and_removes_temporary_file(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "anthropic-api-key"
            target.write_text("previous")
            with patch.object(installer.os, "fchown") as ownership:
                installer.install_key(Path(directory), "sk-ant-synthetic-test")
            self.assertEqual(target.read_text(), "sk-ant-synthetic-test\n")
            self.assertEqual(target.stat().st_mode & 0o777, 0o640)
            self.assertEqual(ownership.call_args.args[1:], (0, 10001))
            self.assertEqual(list(Path(directory).iterdir()), [target])

    def test_failed_replace_preserves_existing_key_and_cleans_temporary_file(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "anthropic-api-key"
            target.write_text("previous")
            with patch.object(installer.os, "fchown"), patch.object(
                    installer.os, "replace", side_effect=OSError("synthetic failure")):
                with self.assertRaises(OSError):
                    installer.install_key(Path(directory), "sk-ant-synthetic-test")
            self.assertEqual(target.read_text(), "previous")
            self.assertEqual(list(Path(directory).iterdir()), [target])


if __name__ == "__main__":
    unittest.main()
