#!/usr/bin/env python3
"""Install the owner's API key without echo, shell history, or a partial credential file."""
import getpass
import os
from pathlib import Path
import socket
import sys
import tempfile


def main():
    if socket.gethostname() != "aegis-dev" or os.geteuid() != 0:
        raise SystemExit("Run with sudo on aegis-dev only.")
    if not sys.stdin.isatty():
        raise SystemExit("Use an interactive SSH terminal; piped input is not accepted.")
    directory = Path("/srv/aegis/deploy/secrets/ai")
    if not directory.is_dir() or directory.is_symlink():
        raise SystemExit("Deploy Aegis first: the AI secret directory must exist.")
    key = getpass.getpass("Claude Console API key (input hidden): ").strip()
    install_key(directory, key)
    print("API key installed. No restart needed. Open Aegis > Status > Check connection.")
    print("Billing and auto-reload are managed separately in Claude Console.")


def install_key(directory, key):
    """Preserve the previous key if validation or writing fails."""
    if not key.startswith("sk-ant-") or any(char.isspace() for char in key):
        raise SystemExit("Invalid API key format; the existing file was not changed.")
    temporary = None
    try:
        fd, temporary = tempfile.mkstemp(prefix=".anthropic-key-", dir=directory)
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            output.write(key + "\n")
            output.flush()
            os.fsync(output.fileno())
            os.fchown(output.fileno(), 0, 10001)
            os.fchmod(output.fileno(), 0o640)
        os.replace(temporary, directory / "anthropic-api-key")
        temporary = None
    finally:
        if temporary is not None:
            Path(temporary).unlink(missing_ok=True)


if __name__ == "__main__":
    main()
