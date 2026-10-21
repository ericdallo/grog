"""Speech to text in a process of its own, which can be held: what's
transcribed as someone talks never takes the CPU from what's transcribed once
they stop (sherpa-onnx can't stop a transcription midway)."""

import atexit
import logging
import os
import signal
import threading
import time

log = logging.getLogger("grog_voice")


class Worker:
    """`make(*args)`, speech to text (audio in, text out), in a process of its
    own started when first needed, with a model of its own (some 700 MB for
    Parakeet), started again if it dies: after `retries` in a row that died
    before answering, only once `retry_s` went by (calls fail meanwhile).
    `hold()` stops it (SIGSTOP), wherever it is, until as many `release()`s
    let it go on (SIGCONT). Once closed, calls fail. What it hears only goes
    to its caller: its errors only say their type."""

    def __init__(self, make, *args, retries=3, retry_s=60.0):
        self._make, self._args = make, args
        self._retries, self._retry_s = retries, retry_s
        self._proc = self._conn = None
        self._calls = threading.Lock()  # one at a time through the pipe
        self._lock = threading.Lock()  # the process, and its holds
        self._holds = 0
        self._died = 0  # processes in a row that died before answering
        self._retry_at = 0.0
        self._closed = self._registered = False

    def __call__(self, pcm):
        with self._calls:
            try:
                conn = self._start()
                conn.send_bytes(pcm)
                ok, text = conn.recv()
            except (OSError, EOFError) as e:
                raise RuntimeError("speech to text stopped") from e
            with self._lock:
                self._died = 0
        if not ok:
            raise RuntimeError(f"speech to text failed: {text}")
        return text

    def start(self):
        """Starts it now, returning once it's ready (else, the first call
        waits for it)."""
        with self._calls:
            try:
                self._start()
            except (OSError, EOFError) as e:
                raise RuntimeError("speech to text stopped") from e

    @property
    def pid(self):
        """Its process's id, while it has one."""
        with self._lock:
            return self._proc.pid if self._proc is not None else None

    def _start(self):
        with self._lock:
            if self._closed:
                raise RuntimeError("speech to text was closed")
            if self._proc is not None and not self._proc.is_alive():
                self._died += 1
                self._conn.close()
                code, self._proc = self._proc.exitcode, None
                if self._died < self._retries:
                    log.warning("speech to text of partials stopped (%s): starting it again", code)
                else:
                    self._retry_at = time.monotonic() + self._retry_s
                    log.warning(
                        "speech to text of partials stopped (%s) %d times in a row: trying again in %.0f s",
                        code,
                        self._died,
                        self._retry_s,
                    )
            if self._proc is None and time.monotonic() < self._retry_at:
                raise RuntimeError("speech to text keeps stopping")
            fresh = self._proc is None
            if fresh:
                import multiprocessing

                # A fresh interpreter: forking one with threads may hang.
                ctx = multiprocessing.get_context("spawn")
                self._conn, theirs = ctx.Pipe()
                self._proc = ctx.Process(target=_serve, args=(theirs, self._make, self._args), daemon=True)
                self._proc.start()
                theirs.close()
                if self._holds:
                    self._signal(signal.SIGSTOP)
                if not self._registered:
                    self._registered = True
                    atexit.register(self.close)
            conn = self._conn
        if fresh:
            conn.recv()  # ready; not under the lock, which holds take
        return conn

    def hold(self):
        with self._lock:
            self._holds += 1
            if self._holds == 1:
                self._signal(signal.SIGSTOP)

    def release(self):
        with self._lock:
            self._holds = max(0, self._holds - 1)
            if not self._holds:
                self._signal(signal.SIGCONT)

    def _signal(self, sig):
        if self._proc is not None:
            try:
                os.kill(self._proc.pid, sig)
            except ProcessLookupError:
                pass

    def close(self):
        """Kills it, held or not: it keeps nothing. Calls fail from then on,
        those under way too."""
        with self._lock:
            self._closed = True
            if self._proc is not None:
                self._proc.kill()
                self._proc.join(5)
                self._proc = None


def _serve(conn, make, args):
    """The process: transcribes what it gets, until its caller is gone."""
    stt = make(*args)
    conn.send(True)  # ready
    while True:
        try:
            pcm = conn.recv_bytes()
        except EOFError:
            return
        try:
            conn.send((True, stt(pcm)))
        except Exception as e:  # its message could quote what was said
            conn.send((False, type(e).__name__))
