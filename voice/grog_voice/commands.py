"""What grog asks of grog-voice (grog's GET /v1/voice/commands): whether
its mic is on, whether grog plays music, the speaker it speaks on (grog's
pick, or none), and as they come, to listen now, without its name (someone
touched grog's face on its screen, say), to stop answering, or which
speakers there are (see speakers.py), making one the music's first, maybe.

From a thread of its own, which connects again, less and less often, while
grog is away: the mic, the music and the speaker stay as grog last said
meanwhile."""

import logging
import threading

from .grog import GrogError

log = logging.getLogger("grog_voice")

# grog didn't say which speaker grog-voice speaks on.
UNSAID = object()


class Commands:
    """Takes what grog asks: `connect()` yields it as (type, data), raising
    GrogError once it can't. It goes to the Voice `attach`ed (set_mic,
    set_music, set_speaker, listen_now, stop, and back: grog answers, at
    first or again after it was away); until then, only whether the mic is
    on, whether music plays, the speaker, and whether grog is back, are
    kept: `mic_on` and `music`, None until grog said, and `speaker`, a
    speaker's name or None, UNSAID until it did. Asked which speakers there
    are, `speakers(request)` answers on a thread of its own, if given."""

    def __init__(self, connect, *, speakers=None, retry_min_s=1.0, retry_max_s=10.0):
        self._connect = connect
        self._speakers = speakers
        self.retry_min_s, self.retry_max_s = retry_min_s, retry_max_s
        self.mic_on = self.music = None
        self.speaker = UNSAID
        self._voice = None
        self._back = False
        self._lock = threading.Lock()
        self._closed = threading.Event()
        threading.Thread(target=self._run, daemon=True, name="commands").start()

    def attach(self, voice):
        """What grog asks goes to `voice` from now on, starting with whether
        its mic is on, whether music plays and the speaker, when grog said,
        and whether grog is back."""
        with self._lock:
            self._voice, mic_on, music, speaker, back = (
                voice,
                self.mic_on,
                self.music,
                self.speaker,
                self._back,
            )
            self._back = False
        if mic_on is not None:
            voice.set_mic(mic_on)
        if music is not None:
            voice.set_music(music)
        if speaker is not UNSAID:
            voice.set_speaker(speaker)
        if back:
            voice.back()

    def close(self):
        self._closed.set()

    def _run(self):
        wait, failing = self.retry_min_s, False
        while not self._closed.is_set():
            try:
                first = True
                for kind, data in self._connect():
                    if self._closed.is_set():
                        return
                    if failing:
                        failing = False
                        log.info("hearing from grog again what to do")
                    wait = self.retry_min_s
                    self._take(kind, data)
                    if first:
                        first = False
                        self._came_back()
            except Exception as e:  # a bug too: the thread goes on, and only says its type
                if not failing:
                    failing = True
                    why = e if isinstance(e, GrogError) else type(e).__name__
                    log.warning("not hearing from grog what to do (%s): it'll try again", why)
            if self._closed.wait(wait):
                return
            wait = min(wait * 2, self.retry_max_s)

    def _came_back(self):
        """grog answers: tells the Voice, or keeps it for the Voice to come."""
        with self._lock:
            voice = self._voice
            if voice is None:
                self._back = True
        if voice is not None:
            voice.back()

    def _take(self, kind, data):
        """Does what grog asks, or keeps it for the Voice to come."""
        data = data if isinstance(data, dict) else {}
        mic = data.get("mic") if kind in ("snapshot", "mic") else None
        music = data.get("music") if kind in ("snapshot", "music") else None
        music = music if isinstance(music, bool) else None
        speaker = data.get("speaker", UNSAID) if kind in ("snapshot", "speaker") else UNSAID
        if not (speaker is UNSAID or speaker is None or (isinstance(speaker, str) and speaker.strip())):
            speaker = UNSAID
        if kind == "speakers" and self._speakers is not None:
            threading.Thread(target=self._speakers, args=(data,), daemon=True, name="speakers").start()
        with self._lock:
            voice = self._voice
            if mic in ("on", "off"):
                self.mic_on = mic == "on"
            if music is not None:
                self.music = music
            if speaker is not UNSAID:
                self.speaker = speaker
        if voice is None:
            return
        if mic in ("on", "off"):
            voice.set_mic(mic == "on")
        if music is not None:
            voice.set_music(music)
        if speaker is not UNSAID:
            voice.set_speaker(speaker)
        if kind == "listen":
            voice.listen_now()
        elif kind == "stop":
            voice.stop()
