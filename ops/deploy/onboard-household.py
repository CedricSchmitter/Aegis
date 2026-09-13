"""Owner-approved anna->lisa, ben->cedric onboarding, preserving IDs, hashes and sessions."""
import json
import os
from pathlib import Path
import socket
import subprocess

DEPLOY = Path("/srv/aegis/deploy")


def main():
    if socket.gethostname() != "aegis-dev" or os.geteuid() != 0:
        raise SystemExit("Run as root only inside aegis-dev")
    seed = DEPLOY / "secrets/initial-members.json"
    original = seed.read_bytes()
    data = json.loads(original)
    mapping = {"anna": ("lisa", "Lisa"), "ben": ("cedric", "Cedric")}
    for member in data["members"]:
        if member["username"] in mapping:
            member["username"], member["displayName"] = mapping[member["username"]]
    if {m["username"] for m in data["members"]} != {"lisa", "cedric"}:
        raise SystemExit("Unexpected seed identities")
    compose = ["docker", "compose", "--env-file", str(DEPLOY / ".env"), "-f", str(DEPLOY / "compose.yaml")]

    def run(args, **kwargs):
        return subprocess.run(args, check=True, stderr=subprocess.PIPE, **kwargs)

    recovery = DEPLOY / "onboarding-recovery"
    recovery.mkdir(mode=0o700, exist_ok=True)
    before = recovery / "initial-members.before.json"
    if not before.exists():
        before.write_bytes(original)
        before.chmod(0o600)
    run(compose + ["stop", "aegis"], stdout=subprocess.DEVNULL)
    try:
        dump = recovery / "before-onboarding.dump"
        if not dump.exists():
            with dump.open("wb") as out:
                run(compose + ["exec", "-T", "postgres", "pg_dump", "-U", "aegis", "-d", "aegis", "-Fc"], stdout=out)
        # Update the seed before database migration so a restart cannot re-create test identities.
        seed.write_text(json.dumps(data, indent=2) + "\n")
        try:
            run(compose + ["exec", "-T", "postgres", "psql", "-U", "aegis", "-d", "aegis", "-v", "ON_ERROR_STOP=1"],
                input=Path(__file__).with_suffix(".sql").read_bytes(), stdout=subprocess.DEVNULL)
        except Exception:
            seed.write_bytes(original)
            raise
    finally:
        run(compose + ["start", "aegis"], stdout=subprocess.DEVNULL)
    print("Onboarded Lisa and Cedric; IDs, passwords, ownership and existing sessions preserved")


if __name__ == "__main__":
    main()
