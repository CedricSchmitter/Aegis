"""The post-deployment cleanup runs against fake docker/findmnt/lsblk/fstrim commands only; nothing touches Docker."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("prune-docker.sh")
CURRENT = "c" * 40
PREVIOUS = "b" * 40
OLDER = "a" * 40
DRILL = "d" * 40

# Each fake appends its arguments to calls.log and answers from environment variables set per test.
FAKES = {
    "sudo": 'if [ "$1" = "-n" ]; then shift; fi\nexec "$@"\n',
    "docker": """echo "docker $*" >> "$FAKE_LOG"
case "$*" in
    "ps -a --format {{.Image}}") [ -z "${FAIL_PS:-}" ] || exit 1; printf '%b' "$CONTAINER_IMAGES" ;;
    "image ls aegis-app --format {{.Tag}}") printf '%b' "$APP_TAGS" ;;
    "builder prune"*) [ -z "${FAIL_BUILDER:-}" ] || { echo "builder boom" >&2; exit 1; } ;;
    "image rm aegis-app:$FAIL_RM") exit 1 ;;
esac
exit 0
""",
    "findmnt": """case "$*" in
    *TARGET*) echo / ;;
    *SOURCE*) echo /dev/sda1 ;;
esac
""",
    "lsblk": 'echo "$DISCARD_MAX"\n',
    "fstrim": 'echo "fstrim $*" >> "$FAKE_LOG"\necho "/: 1 GiB trimmed"\n',
}


@unittest.skipUnless(shutil.which("sh"), "needs a POSIX shell")
class PruneDockerTest(unittest.TestCase):
    def run_prune(self, *args, tags=(CURRENT, PREVIOUS, OLDER, DRILL), containers=(CURRENT, DRILL), **env):
        with tempfile.TemporaryDirectory() as directory:
            bin_dir = Path(directory) / "bin"
            bin_dir.mkdir()
            for name, body in FAKES.items():
                fake = bin_dir / name
                fake.write_bytes(("#!/bin/sh\n" + body).encode())
                fake.chmod(0o755)
            log = Path(directory) / "calls.log"
            log.touch()
            environment = {
                **os.environ,
                "PATH": f"{bin_dir}{os.pathsep}{os.environ['PATH']}",
                "FAKE_LOG": str(log),
                "APP_TAGS": "".join(f"{tag}\\n" for tag in tags),
                "CONTAINER_IMAGES": "".join(f"aegis-app:{tag}\\n" for tag in containers) + "postgres:17.11\\n",
                "DISCARD_MAX": "1G",
                "FAIL_RM": "none",
                **env,
            }
            result = subprocess.run(["sh", str(SCRIPT), *args], env=environment,
                                    capture_output=True, text=True, timeout=30)
            return result, log.read_text().splitlines()

    def removed(self, calls):
        return [call.removeprefix("docker image rm aegis-app:") for call in calls if call.startswith("docker image rm")]

    def test_keeps_current_previous_and_referenced_images_and_bounds_the_cache(self):
        result, calls = self.run_prune(CURRENT, PREVIOUS)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.removed(calls), [OLDER])
        self.assertIn(f"Keeping aegis-app:{DRILL} (referenced by a container)", result.stdout)
        self.assertIn("docker image prune -f", calls)
        self.assertIn("docker builder prune -f --keep-storage 3GB", calls)
        self.assertIn("fstrim -v /", calls)
        self.assertEqual(calls[-1], "docker system df")
        self.assertEqual(result.stderr, "")

    def test_without_a_previous_deployment_only_the_current_image_is_protected(self):
        result, calls = self.run_prune(CURRENT, containers=(CURRENT,), AEGIS_BUILD_CACHE_KEEP="5GB")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.removed(calls), [PREVIOUS, OLDER, DRILL])
        self.assertIn("docker builder prune -f --keep-storage 5GB", calls)

    def test_a_failed_step_is_reported_but_the_remaining_steps_still_run(self):
        result, calls = self.run_prune(CURRENT, PREVIOUS, FAIL_BUILDER="1", FAIL_RM=OLDER)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(f"could not remove aegis-app:{OLDER}", result.stderr)
        self.assertIn("pruning the build cache failed", result.stderr)
        self.assertIn("fstrim -v /", calls)
        self.assertEqual(calls[-1], "docker system df")

    def test_no_image_is_removed_when_containers_cannot_be_listed(self):
        result, calls = self.run_prune(CURRENT, PREVIOUS, FAIL_PS="1")

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.removed(calls), [])
        self.assertIn("old aegis-app images were kept", result.stderr)
        self.assertIn("docker builder prune -f --keep-storage 3GB", calls)

    def test_fstrim_is_skipped_when_the_disk_does_not_support_discard(self):
        result, calls = self.run_prune(CURRENT, PREVIOUS, DISCARD_MAX="0B")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(any(call.startswith("fstrim") for call in calls))
        self.assertIn("Skipping fstrim: /dev/sda1 does not support discard", result.stdout)


if __name__ == "__main__":
    unittest.main()
