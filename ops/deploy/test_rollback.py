"""The rollback runs against fake hostname/sudo/docker commands and a temporary deploy directory; nothing touches Docker."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("rollback.sh")
TARGET = "a" * 40
FAILED = "f" * 40
DEPLOYED = "d" * 40
PREVIOUS = "b" * 40

# Each fake appends its arguments to calls.log and answers from environment variables set per test.
FAKES = {
    "hostname": 'echo "${FAKE_HOSTNAME:-aegis-dev}"\n',
    "sudo": 'if [ "$1" = "-n" ]; then shift; fi\nexec "$@"\n',
    "docker": """echo "docker $*" >> "$FAKE_LOG"
case "$*" in
    "image inspect aegis-app:$MISSING_IMAGE") exit 1 ;;
    compose*" up "*) [ -z "${FAIL_UP:-}" ] || { echo "dependency failed to start" >&2; exit 1; } ;;
    compose*" exec "*) echo "{\\"commit\\":\\"$RUNNING_COMMIT\\"}" ;;
esac
exit 0
""",
}

RELEASE_FILES = ("compose.yaml", "compose.https.yaml", "Caddyfile", "Caddyfile.https",
                 "postgres-init/01-paperless-database.sh")


@unittest.skipUnless(shutil.which("sh"), "needs a POSIX shell")
class RollbackTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        self.bin_dir = root / "bin"
        self.bin_dir.mkdir()
        for name, body in FAKES.items():
            fake = self.bin_dir / name
            fake.write_bytes(("#!/bin/sh\n" + body).encode())
            fake.chmod(0o755)
        self.log = root / "calls.log"
        self.log.touch()
        self.deploy = root / "deploy"
        self.releases = root / "releases"
        (self.deploy / "postgres-init").mkdir(parents=True)
        for name in RELEASE_FILES:
            (self.deploy / name).write_bytes(f"{FAILED} {name}\n".encode())

    def write(self, path, text):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(text.encode())

    def state(self, https=True, deployed=DEPLOYED, previous=PREVIOUS, release=TARGET):
        self.write(self.deploy / ".env", f"POSTGRES_PASSWORD=x\nAEGIS_COMMIT={FAILED}\n"
                   + ("AEGIS_HTTPS=true\n" if https else ""))
        if deployed:
            self.write(self.deploy / "DEPLOYED_COMMIT", deployed + "\n")
        if previous:
            self.write(self.deploy / "PREVIOUS_COMMIT", previous + "\n")
        if release:
            for name in RELEASE_FILES:
                self.write(self.releases / f"aegis-{release}" / "deploy" / name, f"{release} {name}\n")

    def rollback(self, commit=TARGET, **env):
        environment = {
            **os.environ,
            "PATH": f"{self.bin_dir}{os.pathsep}{os.environ['PATH']}",
            "FAKE_LOG": str(self.log),
            "AEGIS_DEPLOY_DIR": str(self.deploy),
            "AEGIS_RELEASES_DIR": str(self.releases),
            "MISSING_IMAGE": "none",
            "RUNNING_COMMIT": commit,
            **env,
        }
        result = subprocess.run(["sh", str(SCRIPT), commit], env=environment,
                                capture_output=True, text=True, timeout=30)
        return result, self.log.read_text().splitlines()

    def read(self, name):
        return (self.deploy / name).read_text().strip()

    def compose_calls(self, calls, action):
        return [call for call in calls if call.startswith("docker compose") and f" {action} " in f"{call} "]

    def test_starts_every_service_of_the_https_stack_not_only_the_application(self):
        self.state()

        result, calls = self.rollback()

        self.assertEqual(result.returncode, 0, result.stderr)
        files = f"-f {self.deploy}/compose.yaml -f {self.deploy}/compose.https.yaml"
        self.assertEqual(self.compose_calls(calls, "up"),
                         [f"docker compose --env-file {self.deploy}/.env {files} up -d --wait --wait-timeout 240"])
        self.assertEqual(len(self.compose_calls(calls, "exec")), 1)
        self.assertIn(f"AEGIS_COMMIT={TARGET}", self.read(".env").splitlines())
        self.assertEqual(self.read("DEPLOYED_COMMIT"), TARGET)

    def test_records_the_replaced_deployment_as_previous_commit(self):
        self.state()

        result, _ = self.rollback()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.read("PREVIOUS_COMMIT"), DEPLOYED)

    def test_after_a_failed_deployment_keeps_previous_commit_and_restores_the_release_compose_files(self):
        # remote-deploy.sh failed before recording FAILED, so DEPLOYED_COMMIT is still the rollback target.
        self.state(deployed=TARGET)

        result, _ = self.rollback()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.read("DEPLOYED_COMMIT"), TARGET)
        self.assertEqual(self.read("PREVIOUS_COMMIT"), PREVIOUS)
        for name in RELEASE_FILES:
            self.assertEqual(self.read(name), f"{TARGET} {name}")

    def test_without_https_only_the_base_compose_file_is_used(self):
        self.state(https=False)

        result, calls = self.rollback()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.compose_calls(calls, "up"),
                         [f"docker compose --env-file {self.deploy}/.env -f {self.deploy}/compose.yaml "
                          "up -d --wait --wait-timeout 240"])

    def test_keeps_the_current_compose_files_when_the_release_is_gone(self):
        self.state(release=None)

        result, _ = self.rollback()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("keeping the current compose files", result.stderr)
        self.assertEqual(self.read("compose.yaml"), f"{FAILED} compose.yaml")

    def test_a_wrong_running_version_does_not_record_the_rollback(self):
        self.state()

        result, _ = self.rollback(RUNNING_COMMIT=FAILED)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.read("DEPLOYED_COMMIT"), DEPLOYED)
        self.assertEqual(self.read("PREVIOUS_COMMIT"), PREVIOUS)

    def test_a_stack_that_does_not_become_healthy_does_not_record_the_rollback(self):
        self.state()

        result, calls = self.rollback(FAIL_UP="1")

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.compose_calls(calls, "exec"), [])
        self.assertEqual(self.read("DEPLOYED_COMMIT"), DEPLOYED)

    def test_refuses_a_commit_without_a_built_image_before_changing_anything(self):
        self.state()

        result, calls = self.rollback(MISSING_IMAGE=TARGET)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(f"No image aegis-app:{TARGET}", result.stderr)
        self.assertEqual(self.compose_calls(calls, "up"), [])
        self.assertIn(f"AEGIS_COMMIT={FAILED}", self.read(".env").splitlines())
        self.assertEqual(self.read("compose.yaml"), f"{FAILED} compose.yaml")

    def test_refuses_another_host(self):
        self.state()

        result, calls = self.rollback(FAKE_HOSTNAME="homeassistant")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("expected host aegis-dev", result.stderr)
        self.assertEqual(calls, [])


if __name__ == "__main__":
    unittest.main()
