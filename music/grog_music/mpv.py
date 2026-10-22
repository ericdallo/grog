"""mpv, started once and started again whenever it dies, controlled over its
JSON IPC: one JSON object per line each way, replies matched to requests by
their request_id, events skipped."""

import json
import logging
import os
import socket
import subprocess
import threading
import time

log = logging.getLogger("grog_music")

# The longest line mpv may send (metadata, playlists).
MAX_LINE = 1024 * 1024


class MpvError(Exception):
    """mpv refused a command (its error, like "property unavailable"), or
    can't be reached."""


def arguments(program, ipc_socket, ytdlp):
    """mpv, idle and waiting for commands, audio only, through the session's
    default sink, with YouTube's audio from yt-dlp. --no-terminal: nothing it
    plays reaches the journal; --no-config: whatever the user's mpv.conf
    says."""
    return [
        program,
        "--idle=yes",
        "--no-video",
        "--no-terminal",
        "--no-config",
        "--force-window=no",
        f"--input-ipc-server={ipc_socket}",
        "--ytdl-format=bestaudio/best",
        f"--script-opts=ytdl_hook-ytdl_path={ytdlp}",
        "--audio-client-name=grog-music",
    ]


class Mpv:
    """mpv at `program`, its IPC at `ipc_socket`. `spawn` starts it
    (subprocess.Popen's arguments); `on_start()` runs each time it (re)starts,
    once it answers. Safe to use from several threads."""

    def __init__(
        self,
        program,
        ipc_socket,
        ytdlp,
        spawn=subprocess.Popen,
        on_start=None,
        start_timeout_s=10.0,
        reply_timeout_s=10.0,
    ):
        self.program, self.ipc_socket, self.ytdlp = program, ipc_socket, ytdlp
        self._spawn, self.on_start = spawn, on_start
        self._start_timeout_s, self._reply_timeout_s = start_timeout_s, reply_timeout_s
        self._proc = self._sock = self._file = None
        self._next_id = 0
        self._lock = threading.RLock()
        self.starts = 0

    def alive(self):
        return self._proc is not None and self._proc.poll() is None and self._sock is not None

    def ensure(self):
        """Starts mpv when it isn't running, or died."""
        with self._lock:
            if not self.alive():
                if self._proc is not None:
                    log.warning("mpv died: starting it again")
                self._start()

    def _start(self):
        self._stop()
        try:
            os.unlink(self.ipc_socket)
        except FileNotFoundError:
            pass
        argv = arguments(self.program, self.ipc_socket, self.ytdlp)
        started = time.monotonic()
        try:
            self._proc = self._spawn(
                argv, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
            )
        except OSError as e:
            raise MpvError(f"couldn't start mpv ({type(e).__name__})") from e
        while True:
            sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            try:
                sock.connect(self.ipc_socket)
                break
            except OSError:
                sock.close()
            if self._proc.poll() is not None:
                self._proc = None
                raise MpvError("mpv exited as it started")
            if time.monotonic() - started > self._start_timeout_s:
                self._stop()
                raise MpvError("mpv didn't start")
            time.sleep(0.02)
        sock.settimeout(self._reply_timeout_s)
        self._sock, self._file = sock, sock.makefile("rb")
        self.starts += 1
        log.info("mpv started in %d ms", (time.monotonic() - started) * 1000)
        if self.on_start is not None:
            self.on_start()

    def _stop(self):
        for f in (self._file, self._sock):
            if f is not None:
                try:
                    f.close()
                except OSError:
                    pass
        self._sock = self._file = None
        if self._proc is not None and self._proc.poll() is None:
            self._proc.kill()
            try:
                self._proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass
        self._proc = None

    def command(self, *command):
        """What mpv answers `command` with (its data). Raises MpvError when
        it refuses, or can't be started. When it went away, it's started
        again (on_start runs) and asked again."""
        with self._lock:
            self.ensure()
            try:
                return self._send(command)
            except (OSError, ValueError) as e:
                log.warning("mpv went away (%s): starting it again", type(e).__name__)
                self._start()
                try:
                    return self._send(command)
                except (OSError, ValueError) as again:
                    self._stop()
                    raise MpvError("mpv isn't answering") from again

    def _send(self, command):
        self._next_id += 1
        request_id = self._next_id
        line = json.dumps({"command": list(command), "request_id": request_id}) + "\n"
        self._sock.sendall(line.encode())
        while True:
            reply = self._file.readline(MAX_LINE)
            if not reply.endswith(b"\n"):
                raise ConnectionError("mpv hung up")
            message = json.loads(reply)
            if message.get("request_id") != request_id:
                continue
            if message.get("error") != "success":
                raise MpvError(str(message.get("error")))
            return message.get("data")

    def get(self, name):
        """Property `name`, or None when mpv has none now (no file, say)."""
        try:
            return self.command("get_property", name)
        except MpvError as e:
            if str(e) in ("property unavailable", "property not found"):
                return None
            raise

    def set(self, name, value):
        self.command("set_property", name, value)

    def close(self):
        """Quits mpv."""
        with self._lock:
            if self.alive():
                try:
                    self._send(("quit",))
                except (OSError, ValueError, MpvError):
                    pass
                try:
                    self._proc.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    pass
            self._stop()
