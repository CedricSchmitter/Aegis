"""The assistant setting is written into a synthetic .env in a temporary directory only."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location(
    "assistant_member", Path(__file__).with_name("set-assistant-member.py"))
assistant = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assistant)


class AssistantMemberTest(unittest.TestCase):
    def env(self, directory, content):
        path = Path(directory) / ".env"
        path.write_text(content, encoding="utf-8")
        os.chmod(path, 0o600)
        return path

    def test_appends_once_and_keeps_every_other_line(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.env(directory, "AEGIS_DB_PASSWORD=synthetic\nAEGIS_COMMIT=abc")

            self.assertTrue(assistant.set_assistants(path, ["Aegis-Dev "]))
            self.assertFalse(assistant.set_assistants(path, ["aegis-dev"]))

            self.assertEqual(path.read_text(encoding="utf-8"),
                             "AEGIS_DB_PASSWORD=synthetic\nAEGIS_COMMIT=abc\nAEGIS_ASSISTANT_MEMBERS=aegis-dev\n")
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.assertEqual(sorted(p.name for p in Path(directory).iterdir()), [".env"])

    def test_replaces_an_existing_value_and_drops_repeated_assignments(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.env(directory, "AEGIS_ASSISTANT_MEMBERS=old\nX=1\nAEGIS_ASSISTANT_MEMBERS=older\n")

            self.assertTrue(assistant.set_assistants(path, ["aegis-dev", "aegis-dev"]))

            self.assertEqual(path.read_text(encoding="utf-8"), "AEGIS_ASSISTANT_MEMBERS=aegis-dev\nX=1\n")

    def test_rejects_invalid_usernames_and_a_missing_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.env(directory, "X=1\n")
            for invalid in ("", "a b", "x;rm", "A" * 41):
                with self.assertRaises(SystemExit):
                    assistant.set_assistants(path, [invalid])
            self.assertEqual(path.read_text(encoding="utf-8"), "X=1\n")
            with self.assertRaises(SystemExit):
                assistant.set_assistants(Path(directory) / "missing.env", ["aegis-dev"])


if __name__ == "__main__":
    unittest.main()
