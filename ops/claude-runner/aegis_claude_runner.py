#!/usr/bin/env python3
"""Aegis Claude Code host runner (protocol 1).

Runs on the Aegis VM as the user who logged in to the official Claude Code CLI and serves the application container
over a Unix socket. Each connection carries one JSON line with the fields of one model call; the runner starts one
headless `claude -p` process with a fixed, locked-down set of flags and answers with one JSON line containing the
CLI's exit code and bounded output. The caller cannot add flags: tools, settings, MCP servers, skills and session
persistence stay disabled. The runner never reads, prints or forwards the CLI login; the CLI uses it itself.

Stops the CLI when its time is up or when the caller closes the connection. Logs only metadata (model, duration,
exit code), never prompts, outputs or credentials.
"""
import argparse
import json
import os
import re
import select
import signal
import socket
import socketserver
import subprocess
import sys
import tempfile
import threading
import time

PROTOCOL = 1
MAX_REQUEST = 2 * 1024 * 1024
MAX_STDOUT = 4 * 1024 * 1024
MAX_STDERR = 64 * 1024
MAX_TIMEOUT_SECONDS = 900
MAX_TURNS = 10
MAX_OUTPUT_TOKENS = 64000
MAX_SCHEMA = 64 * 1024
MODEL = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._\-\[\]]{0,99}$")
EFFORTS = {"low", "medium", "high", "xhigh", "max"}
FIELDS = {"protocol", "model", "systemPrompt", "prompt", "jsonSchema", "effort", "maxTurns", "maxOutputTokens",
          "timeoutSeconds"}
KILL_GRACE_SECONDS = 3


class Rejected(Exception):
    """The request does not match protocol 1; nothing was started."""


def log(message):
    print(message, file=sys.stderr, flush=True)


def _int(request, name, low, high):
    value = request.get(name)
    if not isinstance(value, int) or isinstance(value, bool) or not low <= value <= high:
        raise Rejected(f"{name} must be an integer from {low} to {high}")
    return value


def _text(request, name, allow_empty):
    value = request.get(name)
    if not isinstance(value, str) or (not allow_empty and not value.strip()) or "\x00" in value:
        raise Rejected(f"{name} must be a text")
    return value


def validate(raw):
    """Returns the checked call fields or raises Rejected."""
    try:
        request = json.loads(raw)
    except (ValueError, UnicodeDecodeError) as error:
        raise Rejected("the request is not JSON") from error
    if not isinstance(request, dict):
        raise Rejected("the request must be a JSON object")
    unknown = set(request) - FIELDS
    if unknown:
        raise Rejected("unknown fields: " + ", ".join(sorted(unknown)))
    if request.get("protocol") != PROTOCOL:
        raise Rejected(f"only protocol {PROTOCOL} is supported")
    model = request.get("model")
    if not isinstance(model, str) or not MODEL.match(model):
        raise Rejected("model must be a model name")
    effort = request.get("effort")
    if effort is not None and effort not in EFFORTS:
        raise Rejected("effort must be one of " + ", ".join(sorted(EFFORTS)))
    schema = request.get("jsonSchema")
    if schema is not None and (not isinstance(schema, dict)
                               or len(json.dumps(schema, separators=(",", ":"))) > MAX_SCHEMA):
        raise Rejected(f"jsonSchema must be a JSON object of at most {MAX_SCHEMA} bytes")
    return {
        "model": model,
        "systemPrompt": _text(request, "systemPrompt", False),
        "prompt": _text(request, "prompt", False),
        "jsonSchema": schema,
        "effort": effort,
        "maxTurns": _int(request, "maxTurns", 1, MAX_TURNS),
        "maxOutputTokens": _int(request, "maxOutputTokens", 1, MAX_OUTPUT_TOKENS),
        "timeoutSeconds": _int(request, "timeoutSeconds", 1, MAX_TIMEOUT_SECONDS),
    }


def command(cli, call, system_prompt_file):
    """The only command line the runner executes. Every value comes from a validated field; the system prompt is
    passed as a file so that its text can never be read as an option and is not limited by argument length."""
    args = [cli, "-p", "--output-format", "json", "--model", call["model"],
            "--system-prompt-file", system_prompt_file,
            # No built-in tools, no settings/hooks/skills/plugins/CLAUDE.md, no MCP servers, no slash commands, no
            # stored sessions; anything that would still ask for permission is denied.
            "--tools", "", "--restricted", "--safe-mode", "--strict-mcp-config", "--disable-slash-commands",
            "--no-session-persistence", "--permission-mode", "dontAsk",
            "--max-turns", str(call["maxTurns"])]
    if call["jsonSchema"] is not None:
        args += ["--json-schema", json.dumps(call["jsonSchema"], separators=(",", ":"))]
    if call["effort"] is not None:
        args += ["--effort", call["effort"]]
    return args


def environment(call):
    """A minimal environment: the CLI finds its own login under HOME; no API key or application secret exists here."""
    return {
        "PATH": "/usr/local/bin:/usr/bin:/bin",
        "HOME": os.environ.get("HOME", "/nonexistent"),
        "LANG": "C.UTF-8",
        "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC": "1",
        "DISABLE_AUTOUPDATER": "1",
        "CLAUDE_CODE_SKIP_PROMPT_HISTORY": "1",
        "CLAUDE_CODE_MAX_OUTPUT_TOKENS": str(call["maxOutputTokens"]),
    }


def _read_bounded(path, limit):
    with open(path, "rb") as stream:
        return stream.read(limit).decode("utf-8", errors="replace")


def _stop(process):
    try:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(KILL_GRACE_SECONDS)
    except (ProcessLookupError, subprocess.TimeoutExpired):
        pass
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait()


def _caller_gone(connection):
    """True when the caller closed its end; the protocol sends nothing after the request line."""
    if connection is None:
        return False
    readable, _, _ = select.select([connection], [], [], 0)
    if not readable:
        return False
    try:
        return connection.recv(1, socket.MSG_PEEK) == b""
    except OSError:
        return True


def execute(cli, call, connection=None):
    """Runs one CLI call in an empty working directory and returns the response object."""
    if not os.access(cli, os.X_OK):
        return {"status": "cli_unavailable", "message": "the Claude Code CLI is not installed for the runner user"}
    with tempfile.TemporaryDirectory(prefix="aegis-claude-") as work:
        stdout_path = os.path.join(work, ".stdout")
        stderr_path = os.path.join(work, ".stderr")
        system_path = os.path.join(work, "system-prompt.txt")
        with open(system_path, "w", encoding="utf-8") as system:
            system.write(call["systemPrompt"])
        cwd = os.path.join(work, "cwd")
        os.mkdir(cwd, 0o700)
        with open(stdout_path, "wb") as stdout, open(stderr_path, "wb") as stderr:
            process = subprocess.Popen(command(cli, call, system_path), cwd=cwd, env=environment(call),
                                       stdin=subprocess.PIPE, stdout=stdout, stderr=stderr, start_new_session=True)
        feeder = threading.Thread(target=_feed, args=(process, call["prompt"]), daemon=True)
        feeder.start()
        deadline = time.monotonic() + call["timeoutSeconds"]
        while process.poll() is None:
            if time.monotonic() >= deadline:
                _stop(process)
                return {"status": "timeout",
                        "message": f"the CLI did not finish within {call['timeoutSeconds']} s and was stopped"}
            if _caller_gone(connection):
                _stop(process)
                return {"status": "abandoned", "message": "the caller closed the connection"}
            time.sleep(0.2)
        feeder.join(1)
        return {"status": "completed", "exitCode": process.returncode,
                "stdout": _read_bounded(stdout_path, MAX_STDOUT), "stderr": _read_bounded(stderr_path, MAX_STDERR)}


def _feed(process, prompt):
    try:
        process.stdin.write(prompt.encode("utf-8"))
    except (BrokenPipeError, OSError):
        pass  # The CLI may exit before reading its input; its output explains why.
    finally:
        try:
            process.stdin.close()
        except OSError:
            pass


def _read_line(connection):
    data = bytearray()
    while b"\n" not in data:
        chunk = connection.recv(65536)
        if not chunk:
            break
        data += chunk
        if len(data) > MAX_REQUEST:
            raise Rejected(f"the request is larger than {MAX_REQUEST} bytes")
    return bytes(data).split(b"\n", 1)[0].decode("utf-8")


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        started = time.monotonic()
        connection = self.request
        connection.settimeout(30)
        model = "-"
        try:
            call = validate(_read_line(connection))
            model = call["model"]
            connection.settimeout(None)
            if not self.server.slots.acquire(blocking=False):
                response = {"status": "busy", "message": "the runner is already executing its maximum of calls"}
            else:
                try:
                    response = execute(self.server.cli, call, connection)
                finally:
                    self.server.slots.release()
        except Rejected as error:
            response = {"status": "rejected", "message": str(error)}
        except (OSError, UnicodeDecodeError) as error:
            response = {"status": "failed", "message": type(error).__name__}
        response["protocol"] = PROTOCOL
        log(f"call model={model} status={response['status']} exit={response.get('exitCode', '-')} "
            f"seconds={time.monotonic() - started:.1f}")
        try:
            connection.sendall((json.dumps(response) + "\n").encode("utf-8"))
        except OSError:
            pass  # The caller is gone; the CLI was already stopped.


# Unix sockets exist only on POSIX hosts; the request checks above stay importable elsewhere for tests.
if hasattr(socketserver, "UnixStreamServer"):
    class Server(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
        daemon_threads = True

        def __init__(self, path, cli, max_concurrent):
            self.cli = cli
            self.slots = threading.BoundedSemaphore(max_concurrent)
            super().__init__(path, Handler)


def serve(socket_path, cli, max_concurrent, group):
    if os.path.exists(socket_path):
        os.unlink(socket_path)  # A stale socket of a stopped runner.
    server = Server(socket_path, cli, max_concurrent)
    if group:
        import grp  # POSIX only; imported here so the checks can be tested anywhere.
        os.chown(socket_path, -1, grp.getgrnam(group).gr_gid)
    os.chmod(socket_path, 0o660)

    def stop(signum, frame):
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    log(f"listening on {socket_path} (cli {cli}, at most {max_concurrent} concurrent calls)")
    try:
        server.serve_forever()
    finally:
        server.server_close()
        try:
            os.unlink(socket_path)
        except FileNotFoundError:
            pass


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--socket", required=True, help="Unix socket path shared with the application container")
    parser.add_argument("--cli", default=os.path.expanduser("~/.local/bin/claude"), help="Claude Code CLI binary")
    parser.add_argument("--max-concurrent", type=int, default=2, help="concurrent CLI processes")
    parser.add_argument("--socket-group", default=None, help="group that may connect (the container's group)")
    args = parser.parse_args(argv)
    if args.max_concurrent < 1:
        parser.error("--max-concurrent must be positive")
    serve(args.socket, args.cli, args.max_concurrent, args.socket_group)


if __name__ == "__main__":
    main()
