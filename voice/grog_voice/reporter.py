"""Tells grog what grog-voice is doing, for whoever watches it, like its skin
(grog's POST /v1/voice): loading, listening, hearing (someone talks to grog:
its name came first, or none was needed), awake (it was called, what's said
next needs no wake word), asked (its answer asked something: the reply needs
no wake word either), answering, speaking, announcing (saying what grog
asked it to, though no one asked: a timer going off), error, no-mic. And
what it heard that was for grog, as it comes, while grog shows it (its
`skin.captions` setting): grog says whether it does, and its name, the wake
word. Never what anyone else said.

From a thread of its own, so the voice loop never waits on grog: reports
queue up, the oldest dropped when grog is slow, and are lost when it's down.
The last state goes again every 10 s, so grog knows grog-voice is still
there, also right after it restarts."""

import collections
import logging
import threading

from .grog import GrogError

log = logging.getLogger("grog_voice")

HEARTBEAT_S = 10.0


class Reporter:
    """Call it with a state, `heard` with what was heard for grog, and
    `direction` with where who talks to grog is (see ring.py). `send(state)`
    tells grog, and `send(state, heard)` what was heard too ({"text",
    "final"}), with `direction=` once it's known, returning grog's answer,
    raising GrogError when it can't. `captions`: whether grog shows what's
    heard for it, as it last said; `name`: grog's name, as it last said
    (None before it did)."""

    def __init__(self, send, *, heartbeat_s=HEARTBEAT_S, keep=8):
        self._send, self._heartbeat_s = send, heartbeat_s
        # (state, None), or (None, heard): that goes with the last state;
        # (None, None): the last state again.
        self._queue = collections.deque(maxlen=keep)
        self._last = None
        self._direction = None
        self._busy = self._failing = False
        self.captions = False
        self.name = None
        self._cond = threading.Condition()
        threading.Thread(target=self._run, daemon=True, name="reporter").start()

    def __call__(self, state):
        with self._cond:
            self._queue.append((state, None))
            self._cond.notify_all()

    def heard(self, text, final):
        """What was heard for grog: a guess at what was said so far, or the
        `final` text."""
        with self._cond:
            self._queue.append((None, {"text": text, "final": final}))
            self._cond.notify_all()

    def direction(self, degrees):
        """Where who talks to grog is, in degrees to the right of straight
        ahead: it goes with the last state, now, and with what's told from
        then on."""
        with self._cond:
            self._direction = degrees
            self._queue.append((None, None))
            self._cond.notify_all()

    def flush(self, timeout=2.0):
        """Waits, up to `timeout`, for what's queued to go."""
        with self._cond:
            self._cond.wait_for(lambda: not self._queue and not self._busy, timeout)

    def _next(self):
        """The next (state, heard) to send: the oldest queued, or the last
        state again after a while. None before there's a state."""
        with self._cond:
            if not self._queue:
                self._cond.wait(self._heartbeat_s)
            heard = None
            if self._queue:
                state, heard = self._queue.popleft()
                self._last = state or self._last
            self._busy = self._last is not None
            return (self._last, heard) if self._busy else None

    def _run(self):
        while True:
            if (report := self._next()) is None:
                continue
            state, heard = report
            more = {} if self._direction is None else {"direction": self._direction}
            try:
                answer = self._send(state, **more) if heard is None else self._send(state, heard, **more)
            except Exception as e:  # a bug too: the thread goes on, and only says its type
                if not self._failing:
                    self._failing = True
                    why = e if isinstance(e, GrogError) else type(e).__name__
                    log.warning("couldn't tell grog what grog-voice is doing (%s): it'll try again", why)
            else:
                if isinstance(answer, dict):
                    self.captions = answer.get("captions") is True
                    if isinstance(name := answer.get("name"), str) and name.strip():
                        self.name = name
                if self._failing:
                    self._failing = False
                    log.info("telling grog what grog-voice is doing again")
            finally:
                with self._cond:
                    self._busy = False
                    self._cond.notify_all()
