#!/usr/bin/env python3
"""Mark a login as the household's assistant account (ADR-0017) by setting AEGIS_ASSISTANT_MEMBERS in the VM's .env.

The application designates the account at its next start and shows it as "Aegis"; the login username stays as it
is. Run this before starting a version that removes the subject "both" for the first time, so that the one-time
migration adds only the persons. Idempotent: an unchanged value leaves the file untouched. Prints no other line of
the file, because .env holds secrets.

Usage (on aegis-dev):  python3 set-assistant-member.py aegis-dev [--env-file /srv/aegis/deploy/.env]
"""
import argparse
import os
from pathlib import Path
import re
import sys
import tempfile

KEY = "AEGIS_ASSISTANT_MEMBERS"
USERNAME = re.compile(r"^[a-z0-9][a-z0-9._-]{1,39}$")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("usernames", nargs="+", help="login usernames of the assistant account")
    parser.add_argument("--env-file", default="/srv/aegis/deploy/.env", type=Path)
    args = parser.parse_args(argv)
    changed = set_assistants(args.env_file, args.usernames)
    value = ",".join(normalize(args.usernames))
    if changed:
        print(f"{KEY}={value} written to {args.env_file}.")
        print("Takes effect when the application container starts next (deploy, or recreate the aegis service).")
    else:
        print(f"{KEY} is already {value}; nothing changed.")


def normalize(usernames):
    result = []
    for raw in usernames:
        name = raw.strip().lower()
        if not USERNAME.match(name):
            raise SystemExit(f"Not a valid username: {raw!r}")
        if name not in result:
            result.append(name)
    return result


def set_assistants(env_file, usernames):
    """Replace or append the one assignment, keep every other line and the file mode; return whether it changed."""
    value = ",".join(normalize(usernames))
    if not env_file.is_file() or env_file.is_symlink():
        raise SystemExit(f"{env_file} does not exist; run init-secrets.sh first.")
    original = env_file.read_text(encoding="utf-8")
    lines = original.splitlines(keepends=True)
    assignment = f"{KEY}={value}\n"
    found = False
    for index, line in enumerate(lines):
        if line.split("=", 1)[0].strip() == KEY:
            lines[index] = "" if found else assignment
            found = True
    if not found:
        if lines and not lines[-1].endswith("\n"):
            lines[-1] += "\n"
        lines.append(assignment)
    updated = "".join(lines)
    if updated == original:
        return False
    mode = env_file.stat().st_mode & 0o777
    temporary = None
    try:
        fd, temporary = tempfile.mkstemp(prefix=".env-", dir=env_file.parent)
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            output.write(updated)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, env_file)
        temporary = None
    finally:
        if temporary is not None:
            Path(temporary).unlink(missing_ok=True)
    return True


if __name__ == "__main__":
    main(sys.argv[1:])
