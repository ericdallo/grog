import json
import os
import socket
import stat
import unittest

from fakes import SECRET_QUERY, temp_dir

from grog_music import Refused
from grog_music.mpv import MpvError
from grog_music.server import MAX_LINE, Server, ask, handle


def boom(_):
    raise KeyError(SECRET_QUERY)


COMMANDS = {
    "status": lambda r: {"state": "idle"},
    "echo": lambda r: {"said": r.get("query")},
    "refuse": lambda r: (_ for _ in ()).throw(Refused("nothing found for that")),
    "broken": lambda r: (_ for _ in ()).throw(MpvError("mpv didn't start")),
    "boom": boom,
}


def raw(path, data):
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
        sock.settimeout(5)
        sock.connect(path)
        sock.sendall(data)
        sock.shutdown(socket.SHUT_WR)
        out = b""
        while chunk := sock.recv(65536):
            out += chunk
        return out


class HandleTest(unittest.TestCase):
    def test_runs_the_command(self):
        self.assertEqual(
            ({"ok": True, "state": "idle"}, ("status", "ok")), handle(b'{"command":"status"}', COMMANDS)
        )

    def test_bad_requests(self):
        for line, error in (
            (b"not json", "send one JSON object per line"),
            (b"[1]", 'send {"command": "..."}'),
            (b'{"command": 3}', 'send {"command": "..."}'),
        ):
            self.assertEqual({"ok": False, "error": error}, handle(line, COMMANDS)[0])

    def test_unknown_command(self):
        reply, (name, outcome) = handle(b'{"command":"dance"}', COMMANDS)
        self.assertFalse(reply["ok"])
        self.assertIn("unknown command", reply["error"])
        self.assertIn("status", reply["error"])
        self.assertEqual(("?", "unknown command"), (name, outcome))

    def test_failures_are_plain_sentences(self):
        self.assertEqual(
            {"ok": False, "error": "nothing found for that"}, handle(b'{"command":"refuse"}', COMMANDS)[0]
        )
        self.assertEqual(
            {"ok": False, "error": "the player isn't working right now"},
            handle(b'{"command":"broken"}', COMMANDS)[0],
        )
        reply, (_, outcome) = handle(b'{"command":"boom"}', COMMANDS)
        self.assertEqual({"ok": False, "error": "grog-music failed doing that"}, reply)
        self.assertEqual("failed (KeyError)", outcome)


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.path = os.path.join(temp_dir(self), "music.sock")
        with open(self.path, "w"):
            pass  # a stale one
        self.server = Server(self.path, COMMANDS).start()
        self.addCleanup(self.server.close)

    def test_one_line_in_one_line_out(self):
        self.assertEqual({"ok": True, "said": "olá"}, ask(self.path, {"command": "echo", "query": "olá"}))
        reply = raw(self.path, b'{"command": "status"}\n')
        self.assertTrue(reply.endswith(b"\n"))
        self.assertEqual(1, reply.count(b"\n"))
        self.assertEqual({"ok": True, "state": "idle"}, json.loads(reply))

    def test_only_its_user_and_group(self):
        self.assertTrue(stat.S_ISSOCK(os.stat(self.path).st_mode))
        self.assertEqual(0o660, stat.S_IMODE(os.stat(self.path).st_mode))

    def test_a_line_too_long(self):
        reply = json.loads(
            raw(self.path, b'{"command": "echo", "query": "' + b"x" * (MAX_LINE + 10) + b'"}\n')
        )
        self.assertFalse(reply["ok"])
        self.assertIn("at most", reply["error"])

    def test_a_line_without_its_end(self):
        self.assertEqual({"ok": True, "state": "idle"}, json.loads(raw(self.path, b'{"command": "status"}')))

    def test_logs_commands_and_outcomes_never_what_was_asked(self):
        with self.assertLogs("grog_music", "INFO") as logs:
            ask(self.path, {"command": "echo", "query": SECRET_QUERY})
            ask(self.path, {"command": "boom", "query": SECRET_QUERY})
            raw(self.path, SECRET_QUERY.encode() + b"\n")
            self.server.close()
        text = "\n".join(logs.output)
        self.assertNotIn(SECRET_QUERY, text)
        self.assertRegex(text, r"echo: ok in \d+ ms")
        self.assertRegex(text, r"boom: failed \(KeyError\) in \d+ ms")

    def test_close_removes_the_socket(self):
        self.server.close()
        self.assertFalse(os.path.exists(self.path))


if __name__ == "__main__":
    unittest.main()
