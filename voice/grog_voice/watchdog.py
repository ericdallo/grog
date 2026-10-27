"""Tells systemd how grog-voice is (sd_notify, over its NOTIFY_SOCKET):
ready, once its models are loaded and its mic open, then alive, at half its
WatchdogSec, while it's healthy: its voice takes what the mic hears, or has
nothing to take (its mic switched off, or none). Stuck, it stops saying it,
and systemd restarts it. Without systemd, or without a watchdog, nothing."""

import logging
import os
import socket
import threading

log = logging.getLogger("grog_voice")


def notify(message, environ=os.environ):
    """Sends `message` ("READY=1", "WATCHDOG=1") to systemd, when it listens
    (NOTIFY_SOCKET): whether it went."""
    path = environ.get("NOTIFY_SOCKET")
    if not path:
        return False
    if path.startswith("@"):  # an abstract socket
        path = "\0" + path[1:]
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM | socket.SOCK_CLOEXEC) as s:
            s.connect(path)
            s.sendall(message.encode())
        return True
    except OSError:
        return False


def interval(environ=os.environ, pid=None):
    """How often to say it's alive, in seconds: half its WatchdogSec
    (WATCHDOG_USEC), when that's for this process (WATCHDOG_PID); None
    without one."""
    usec, for_pid = environ.get("WATCHDOG_USEC"), environ.get("WATCHDOG_PID")
    if not usec or (for_pid and for_pid != str(os.getpid() if pid is None else pid)):
        return None
    try:
        seconds = int(usec) / 1e6 / 2
    except ValueError:
        return None
    return seconds if seconds > 0 else None


class Watchdog:
    """Says grog-voice is alive every `every_s` (see interval), while
    `healthy()`, on a thread of its own, once started; `send` tells systemd
    (see notify). Once it isn't healthy, that's logged, once until it is
    again."""

    def __init__(self, healthy, every_s, send=notify):
        self._healthy, self._every_s, self._send = healthy, every_s, send
        self._closed = threading.Event()
        self._sick = False

    def start(self):
        threading.Thread(target=self._run, daemon=True, name="watchdog").start()

    def close(self):
        self._closed.set()

    def tick(self):
        """Says it's alive, if it's healthy."""
        try:
            healthy = self._healthy()
        except Exception as e:  # a bug: as if stuck, saying only its type
            log.error("couldn't tell whether grog-voice is healthy (%s)", type(e).__name__)
            healthy = False
        if healthy:
            if self._sick:
                self._sick = False
                log.info("grog-voice hears again: telling systemd it's alive")
            self._send("WATCHDOG=1")
        elif not self._sick:
            self._sick = True
            log.warning("grog-voice stopped taking what its mic hears: systemd will restart it")

    def _run(self):
        while True:
            self.tick()
            if self._closed.wait(self._every_s):
                return
