"""Hermetic tests of the Claude Code host runner against a stand-in CLI; no login, network or real CLI is used.

Process and socket tests need a POSIX host (the Aegis VM); request checks and the command line run anywhere.
"""
import json
import os
from pathlib import Path
import socket
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import aegis_claude_runner as runner

POSIX = os.name == "posix"
UNIX_SOCKETS = hasattr(socket, "AF_UNIX") and POSIX

FAKE_CLI = """#!{python}
import json, os, sys, time
record = {record!r}
args = sys.argv[1:]
model = args[args.index("--model") + 1]
system_file = args[args.index("--system-prompt-file") + 1]
with open(system_file, encoding="utf-8") as stream:
    system = stream.read()
entry = {{"args": args, "env": sorted(os.environ), "cwd": os.getcwd(), "cwdEntries": os.listdir("."),
          "prompt": sys.stdin.read(), "system": system, "pid": os.getpid(),
          "maxOutputTokens": os.environ.get("CLAUDE_CODE_MAX_OUTPUT_TOKENS")}}
with open(record, "w", encoding="utf-8") as stream:
    json.dump(entry, stream)
if model == "slow":
    time.sleep(60)
if model == "fails":
    sys.stderr.write("Not logged in")
    sys.exit(1)
print(json.dumps({{"type": "result", "subtype": "success", "is_error": False, "result": "ok",
                  "structured_output": {{"status": "ok"}}}}))
"""


def request(**overrides):
    fields = {"protocol": 1, "model": "claude-sonnet-5", "systemPrompt": "You classify documents.",
              "prompt": "Classify this.", "jsonSchema": {"type": "object"}, "effort": "low", "maxTurns": 3,
              "maxOutputTokens": 2048, "timeoutSeconds": 30}
    fields.update(overrides)
    return {key: value for key, value in fields.items() if value is not ...}


class ValidationTest(unittest.TestCase):
    def test_accepts_a_complete_request(self):
        call = runner.validate(json.dumps(request()))
        self.assertEqual("claude-sonnet-5", call["model"])
        self.assertEqual(2048, call["maxOutputTokens"])

    def test_accepts_the_family_aliases_that_select_the_newest_model(self):
        for alias in ("opus", "sonnet", "haiku"):
            self.assertEqual(alias, runner.validate(json.dumps(request(model=alias)))["model"])

    def test_optional_schema_and_effort_may_be_null(self):
        call = runner.validate(json.dumps(request(jsonSchema=None, effort=None)))
        self.assertIsNone(call["jsonSchema"])
        self.assertIsNone(call["effort"])

    def test_rejects_anything_outside_protocol_1(self):
        invalid = [
            "not json", "[]", json.dumps(request(protocol=2)), json.dumps(request(extraFlags=["--verbose"])),
            json.dumps(request(model="--dangerously-skip-permissions")), json.dumps(request(model="a b")),
            json.dumps(request(effort="extreme")), json.dumps(request(jsonSchema="{}")),
            json.dumps(request(maxTurns=True)), json.dumps(request(maxTurns=0)), json.dumps(request(maxTurns=11)),
            json.dumps(request(timeoutSeconds=901)), json.dumps(request(maxOutputTokens="2048")),
            json.dumps(request(prompt=" ")), json.dumps(request(systemPrompt=None)),
            json.dumps(request(prompt=...)), json.dumps(request(prompt="a\x00b")),
            json.dumps(request(jsonSchema={"description": "x" * (runner.MAX_SCHEMA + 1)})),
        ]
        for raw in invalid:
            with self.subTest(raw=raw[:60]), self.assertRaises(runner.Rejected):
                runner.validate(raw)


class CommandTest(unittest.TestCase):
    def test_command_line_is_locked_down(self):
        call = runner.validate(json.dumps(request(systemPrompt="--dangerously-skip-permissions")))
        args = runner.command("/opt/claude", call, "/tmp/work/system-prompt.txt")
        self.assertEqual(["/opt/claude", "-p", "--output-format", "json", "--model", "claude-sonnet-5"], args[:6])
        self.assertEqual("", args[args.index("--tools") + 1])
        for flag in ["--restricted", "--safe-mode", "--strict-mcp-config", "--disable-slash-commands",
                     "--no-session-persistence"]:
            self.assertIn(flag, args)
        self.assertEqual("dontAsk", args[args.index("--permission-mode") + 1])
        self.assertEqual("3", args[args.index("--max-turns") + 1])
        self.assertEqual("/tmp/work/system-prompt.txt", args[args.index("--system-prompt-file") + 1])
        self.assertEqual('{"type":"object"}', args[args.index("--json-schema") + 1])
        self.assertEqual("low", args[args.index("--effort") + 1])
        self.assertNotIn("--dangerously-skip-permissions", args)
        self.assertFalse(any(arg in args for arg in ["--mcp-config", "--settings", "--allowedTools", "--add-dir"]))

    def test_optional_flags_are_omitted(self):
        call = runner.validate(json.dumps(request(jsonSchema=None, effort=None)))
        args = runner.command("/opt/claude", call, "/tmp/s")
        self.assertNotIn("--json-schema", args)
        self.assertNotIn("--effort", args)

    def test_environment_carries_no_inherited_secrets(self):
        call = runner.validate(json.dumps(request()))
        with patch.dict(os.environ, {"ANTHROPIC_API_KEY": "sk-ant-api03-x", "AEGIS_DB_PASSWORD": "x",
                                     "HOME": "/home/aegis-agent"}):
            env = runner.environment(call)
        self.assertEqual("/home/aegis-agent", env["HOME"])
        self.assertEqual("2048", env["CLAUDE_CODE_MAX_OUTPUT_TOKENS"])
        self.assertNotIn("ANTHROPIC_API_KEY", env)
        self.assertFalse(any(name.startswith("AEGIS_") for name in env))


class FakeCliCase(unittest.TestCase):
    """Installs the stand-in CLI; the tests live in the subclasses."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.record = root / "record.json"
        self.cli = root / "claude"
        self.cli.write_text(FAKE_CLI.format(python=sys.executable, record=str(self.record)), encoding="utf-8")
        self.cli.chmod(0o755)

    def call(self, **overrides):
        return runner.validate(json.dumps(request(**overrides)))

    def recorded(self):
        return json.loads(self.record.read_text(encoding="utf-8"))

    def assertProcessGone(self, pid):
        for _ in range(50):
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                return
            time.sleep(0.1)
        self.fail(f"process {pid} is still running")


@unittest.skipUnless(POSIX, "starts processes in their own session; runs on the Aegis VM")
class ExecutionTest(FakeCliCase):
    def test_runs_the_cli_in_an_empty_directory_with_prompt_on_stdin(self):
        response = runner.execute(str(self.cli), self.call())
        self.assertEqual("completed", response["status"])
        self.assertEqual(0, response["exitCode"])
        self.assertEqual("ok", json.loads(response["stdout"])["result"])
        seen = self.recorded()
        self.assertEqual("Classify this.", seen["prompt"])
        self.assertEqual("You classify documents.", seen["system"])
        self.assertEqual([], seen["cwdEntries"])
        self.assertEqual("2048", seen["maxOutputTokens"])
        self.assertNotIn("ANTHROPIC_API_KEY", seen["env"])
        self.assertFalse(Path(seen["cwd"]).exists(), "the working directory is removed afterwards")

    def test_cli_failures_are_returned_as_output(self):
        response = runner.execute(str(self.cli), self.call(model="fails"))
        self.assertEqual("completed", response["status"])
        self.assertEqual(1, response["exitCode"])
        self.assertEqual("Not logged in", response["stderr"])

    def test_overdue_cli_is_stopped(self):
        started = time.monotonic()
        response = runner.execute(str(self.cli), self.call(model="slow", timeoutSeconds=1))
        self.assertEqual("timeout", response["status"])
        self.assertLess(time.monotonic() - started, 10)
        self.assertProcessGone(self.recorded()["pid"])

    def test_missing_cli_is_reported(self):
        response = runner.execute(str(self.cli) + "-missing", self.call())
        self.assertEqual("cli_unavailable", response["status"])


@unittest.skipUnless(UNIX_SOCKETS, "needs Unix sockets; runs on the Aegis VM")
class ServerTest(FakeCliCase):
    def start(self, max_concurrent=2):
        self.socket_path = os.path.join(self.temp.name, "runner.sock")
        server = runner.Server(self.socket_path, str(self.cli), max_concurrent)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)

    def connect(self, **overrides):
        client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        client.connect(self.socket_path)
        client.sendall((json.dumps(request(**overrides)) + "\n").encode("utf-8"))
        return client

    @staticmethod
    def answer(client):
        data = b""
        while True:
            chunk = client.recv(65536)
            if not chunk:
                break
            data += chunk
        client.close()
        return json.loads(data)

    def wait_for_record(self):
        for _ in range(100):
            if self.record.exists() and self.record.stat().st_size > 0:
                return self.recorded()
            time.sleep(0.05)
        self.fail("the CLI was not started")

    def test_answers_one_call_per_connection(self):
        self.start()
        response = self.answer(self.connect())
        self.assertEqual(1, response["protocol"])
        self.assertEqual("completed", response["status"])

    def test_invalid_requests_start_nothing(self):
        self.start()
        response = self.answer(self.connect(model="--help"))
        self.assertEqual("rejected", response["status"])
        self.assertFalse(self.record.exists())

    def test_calls_beyond_the_limit_are_refused_as_busy(self):
        self.start(max_concurrent=1)
        slow = self.connect(model="slow")
        self.wait_for_record()
        self.assertEqual("busy", self.answer(self.connect())["status"])
        slow.close()

    def test_closing_the_connection_stops_the_cli(self):
        self.start()
        client = self.connect(model="slow")
        pid = self.wait_for_record()["pid"]
        client.close()
        self.assertProcessGone(pid)


if __name__ == "__main__":
    unittest.main()
