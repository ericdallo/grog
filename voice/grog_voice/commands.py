"""What grog asks of grog-voice (grog's GET /v1/voice/commands): whether
its mic is on, and as they come, to listen now, without its name (someone
touched grog's face on its screen, say), or to stop answering.

From a thread of its own, which connects again, less and less often, while
grog is away: the mic stays as grog last said meanwhile."""

import logging
import threading

from .grog import GrogError

log = logging.getLogger("grog_voice")


class Commands:
    """Takes what grog asks: `connect()` yields it as (type, data), raising
    GrogError once it can't. It goes to the Voice `attach`ed (set_mic,
    listen_now, stop); until then, only whether the mic is on is kept:
    `mic_on`, None until grog said."""

    def __init__(self, connect, *, retry_min_s=1.0, retry_max_s=10.0):
        self._connect = connect
        self.retry_min_s, self.retry_max_s = retry_min_s, retry_max_s
        self.mic_on = None
        self._voice = None
        self._lock = threading.Lock()
        self._closed = threading.Event()
        threading.Thread(target=self._run, daemon=True, name="commands").start()

    def attach(self, voice):
        """What grog asks goes to `voice` from now on, starting with whether
        its mic is on, when grog said."""
        with self._lock:
            self._voice, mic_on = voice, self.mic_on
        if mic_on is not None:
            voice.set_mic(mic_on)

    def close(self):
        self._closed.set()

    def _run(self):
        wait, failing = self.retry_min_s, False
        while not self._closed.is_set():
            try:
                for kind, data in self._connect():
                    if self._closed.is_set():
                        return
                    if failing:
                        failing = False
                        log.info("hearing from grog again what to do")
                    wait = self.retry_min_s
                    self._take(kind, data)
            except Exception as e:  # a bug too: the thread goes on, and only says its type
                if not failing:
                    failing = True
                    why = e if isinstance(e, GrogError) else type(e).__name__
                    log.warning("not hearing from grog what to do (%s): it'll try again", why)
            if self._closed.wait(wait):
                return
            wait = min(wait * 2, self.retry_max_s)

    def _take(self, kind, data):
        """Does what grog asks, or keeps it for the Voice to come."""
        mic = data.get("mic") if kind in ("snapshot", "mic") and isinstance(data, dict) else None
        with self._lock:
            voice = self._voice
            if mic in ("on", "off"):
                self.mic_on = mic == "on"
        if voice is None:
            return
        if mic in ("on", "off"):
            voice.set_mic(mic == "on")
        elif kind == "listen":
            voice.listen_now()
        elif kind == "stop":
            voice.stop()
