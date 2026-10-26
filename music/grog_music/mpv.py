"""mpv, started once and started again whenever it dies, controlled over its
JSON IPC: one JSON object per line each way, replies matched to requests by
their request_id, events handed to whoever wants them as they're read."""

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


# Replies not coming in time, in a row, before mpv counts as stuck and is
# started again.
MAX_TIMEOUTS = 3


class MpvError(Exception):
    """mpv refused a command (its error, like "property unavailable"), or
    can't be reached."""


class MpvTimeout(MpvError):
    """mpv didn't answer a command in time: it still runs, with its queue."""


def arguments(program, ipc_socket, ytdlp, audio_device=None):
    """mpv, idle and waiting for commands, audio only, through `audio_device`
    (mpv's --audio-device) or the session's default sink, with YouTube's
    audio from yt-dlp. --no-terminal: nothing it plays reaches the journal;
    --no-config: whatever the user's mpv.conf says."""
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
    ] + ([f"--audio-device={audio_device}"] if audio_device else [])


class Mpv:
    """mpv at `program`, its IPC at `ipc_socket`, playing into `audio_device`
    (None: the default). `spawn` starts it (subprocess.Popen's arguments);
    `on_start()` runs each time it (re)starts, once it answers;
    `on_event(event)` gets each of its events (a dict), read while waiting
    for a reply, so those before a command come before its reply. Safe to
    use from several threads."""

    def __init__(
        self,
        program,
        ipc_socket,
        ytdlp,
        spawn=subprocess.Popen,
        on_start=None,
        start_timeout_s=10.0,
        reply_timeout_s=10.0,
        audio_device=None,
        on_event=None,
    ):
        self.program, self.ipc_socket, self.ytdlp = program, ipc_socket, ytdlp
        self.audio_device = audio_device
        self._spawn, self.on_start, self.on_event = spawn, on_start, on_event
        self._start_timeout_s, self._reply_timeout_s = start_timeout_s, reply_timeout_s
        self._proc = self._sock = None
        self._buffer = b""
        self._timeouts = 0
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
        argv = arguments(self.program, self.ipc_socket, self.ytdlp, self.audio_device)
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
        self._sock, self._buffer, self._timeouts = sock, b"", 0
        self.starts += 1
        log.info("mpv started in %d ms", (time.monotonic() - started) * 1000)
        if self.on_start is not None:
            self.on_start()

    def _stop(self):
        if self._sock is not None:
            try:
                self._sock.close()
            except OSError:
                pass
        self._sock, self._buffer = None, b""
        if self._proc is not None and self._proc.poll() is None:
            self._proc.kill()
            try:
                self._proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass
        self._proc = None

    def command(self, *command):
        """What mpv answers `command` with (its data). Raises MpvError when
        it refuses, or can't be started, and MpvTimeout when its reply
        doesn't come in time (it keeps running, unless that happened
        MAX_TIMEOUTS times in a row). When it went away, it's started again
        (on_start runs) and asked again."""
        with self._lock:
            self.ensure()
            try:
                return self._send(command)
            except MpvTimeout:
                self._timeouts += 1
                if self._timeouts < MAX_TIMEOUTS:
                    raise
                log.warning("mpv stopped answering: starting it again")
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
            try:
                message = json.loads(self._read_line())
            except TimeoutError as e:
                raise MpvTimeout("mpv didn't answer in time") from e
            if not isinstance(message, dict):
                continue
            if "event" in message:
                self._event(message)
                continue
            if message.get("request_id") != request_id:
                continue
            self._timeouts = 0
            if message.get("error") != "success":
                raise MpvError(str(message.get("error")))
            return message.get("data")

    def _read_line(self):
        """The next line mpv sent. What was read stays buffered when the
        reply doesn't come in time, so the connection is still good for the
        next command (a socket's makefile isn't, after a timeout)."""
        while b"\n" not in self._buffer:
            if len(self._buffer) > MAX_LINE:
                raise ValueError("mpv sent too long a line")
            chunk = self._sock.recv(65536)
            if not chunk:
                raise ConnectionError("mpv hung up")
            self._buffer += chunk
        line, self._buffer = self._buffer.split(b"\n", 1)
        return line

    def _event(self, event):
        if self.on_event is None:
            return
        try:
            self.on_event(event)
        except Exception as e:
            log.warning("handling an mpv event failed (%s)", type(e).__name__)

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
