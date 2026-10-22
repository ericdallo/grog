"""grog-music's socket: a unix socket only its user and group reach, one
connection per request, one JSON object per line each way (keys
lowerCamelCase). Replies are {"ok": true, ...} or {"ok": false, "error": "<a
plain sentence>"}. It logs which command ran, how it went and how long it
took: never what was asked or played."""

import json
import logging
import os
import socket
import threading
import time

from . import Refused
from .mpv import MpvError

log = logging.getLogger("grog_music")

# The longest request line.
MAX_LINE = 256 * 1024
# How long a connection may take to send its request.
READ_TIMEOUT_S = 10.0


def _error(message):
    return {"ok": False, "error": message}


def handle(line, commands):
    """The reply to a request `line` (bytes), running its command from
    `commands` ({name: function of the request}), and the outcome to log
    (the command when known, how it went)."""
    try:
        request = json.loads(line)
    except ValueError:
        return _error("send one JSON object per line"), ("?", "not JSON")
    if not isinstance(request, dict) or not isinstance(request.get("command"), str):
        return _error('send {"command": "..."}'), ("?", "no command")
    name = request["command"]
    run = commands.get(name)
    if run is None:
        return _error(f"unknown command; known ones: {', '.join(sorted(commands))}"), ("?", "unknown command")
    try:
        return {"ok": True} | run(request), (name, "ok")
    except Refused as e:
        return _error(str(e)), (name, "refused")
    except MpvError:
        return _error("the player isn't working right now"), (name, "mpv failed")
    except Exception as e:
        return _error("grog-music failed doing that"), (name, f"failed ({type(e).__name__})")


def _read_line(conn):
    """The request line, or None when it's too long or never ends."""
    data = b""
    while b"\n" not in data:
        chunk = conn.recv(65536)
        if not chunk:
            return data or None
        data += chunk
        if len(data) > MAX_LINE + 1:
            return None
    line = data.split(b"\n", 1)[0]
    return line if len(line) <= MAX_LINE else None


def serve_connection(conn, commands):
    with conn:
        started = time.monotonic()
        conn.settimeout(READ_TIMEOUT_S)
        try:
            line = _read_line(conn)
        except OSError as e:
            log.info("a request failed: %s", type(e).__name__)
            return
        if line is None:
            reply, (name, outcome) = (
                _error(f"send one line of JSON, at most {MAX_LINE} bytes"),
                ("?", "too long"),
            )
        else:
            reply, (name, outcome) = handle(line, commands)
        try:
            conn.sendall(json.dumps(reply, ensure_ascii=False).encode() + b"\n")
        except OSError as e:
            outcome += f", not answered ({type(e).__name__})"
        log.info("%s: %s in %d ms", name, outcome, (time.monotonic() - started) * 1000)


class Server:
    """Listens at `path` (read-write for its user and group only), running
    each connection's command from `commands` on a thread of its own."""

    def __init__(self, path, commands):
        self.path, self.commands = path, commands
        self._sock = None

    def start(self):
        try:
            os.unlink(self.path)
        except FileNotFoundError:
            pass
        self._sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self._sock.bind(self.path)
        os.chmod(self.path, 0o660)
        self._sock.listen(16)
        log.info("listening on %s", self.path)
        threading.Thread(target=self._accept, daemon=True, name="accept").start()
        return self

    def _accept(self):
        while True:
            try:
                conn, _ = self._sock.accept()
            except OSError as e:
                if self._sock.fileno() != -1:
                    log.info("accepting failed: %s", type(e).__name__)
                    time.sleep(0.1)
                    continue
                return
            threading.Thread(target=serve_connection, args=(conn, self.commands), daemon=True).start()

    def close(self):
        if self._sock is not None:
            self._sock.close()
            try:
                os.unlink(self.path)
            except FileNotFoundError:
                pass


def ask(path, request, timeout_s=60.0):
    """Sends `request` to grog-music at `path`: its reply."""
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
        sock.settimeout(timeout_s)
        sock.connect(path)
        sock.sendall(json.dumps(request).encode() + b"\n")
        data = b""
        while not data.endswith(b"\n"):
            chunk = sock.recv(65536)
            if not chunk:
                break
            data += chunk
        return json.loads(data)
