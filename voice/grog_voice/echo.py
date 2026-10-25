"""grog's own sound, as its mic hears it. grog-voice listens while grog
answers, to hear someone call it then, but its mic also hears grog's own
voice and earcons, from its speaker, which the VAD takes for speech. So
while they play, what the mic hears no louder than their echo doesn't
start an utterance: only someone heard over grog is heard at all, which is
also when speech to text makes out what they say rather than grog's words.
Once someone is heard, the rest of what they say is, as usual.

How loud their echo is comes from what the mic heard while grog spoke (not
while it played an earcon, quieter): its loudest (the 90th percentile of
those frames' levels, over the last 10 s of them). With grog's speaker
right next to its mic, as on a laptop, its voice reaches the mic louder
than anyone talking to it (~25 dB, clipping it), and nothing is heard over
it; with its speaker farther away, or quieter, someone nearer the mic is
(plans/voice.md, "Interruptions")."""

import collections
import threading
import time

from .audio import rms_dbfs

# Once grog's sound was due to end, how long its echo may still be heard:
# the room's, the speaker's own delay, and the mic's frames stamped late
# under load (~0.35 s seen). What's said meanwhile isn't lost: an utterance
# starting after it keeps what came before (the endpointer's preroll).
TAIL_S = 0.5
# How much louder than its echo's loudest someone has to be, to be heard
# over it.
MARGIN_DB = 6.0
# Its echo's loudest: this percentile of the levels of what the mic heard
# while grog spoke, over the last KEEP_S of it, once there's LEARN_S of it.
# Until then, nothing is heard over grog's sound.
PERCENTILE = 90
KEEP_S = 10.0
LEARN_S = 1.0
# How many stretches of grog's sound are kept: frames are read a moment
# late at most.
KEPT_SPANS = 32


def _add(spans, start, end):
    """Adds the stretch from `start` to `end` to `spans` ([start, end],
    oldest first), with the last one when they touch."""
    if spans and spans[-1][1] >= start:
        spans[-1][1] = max(spans[-1][1], end)
    else:
        spans.append([start, end])


class Echo:
    """When grog's sound plays (`played`, as it's written to a speaker),
    and what the mic hears meanwhile (`gate`). `frame_s`: how long the mic's
    frames last; `clock` tells the time in seconds, as frames are stamped
    (time.monotonic). `limit`: how loud what's heard over grog's sound has
    to be, once learned (dBFS)."""

    def __init__(self, frame_s, clock=time.monotonic):
        self.clock = clock
        self.limit = None
        # Its sound, and its speech among it: [start, end], oldest first.
        self._sound = collections.deque(maxlen=KEPT_SPANS)
        self._speech = collections.deque(maxlen=KEPT_SPANS)
        self._lock = threading.Lock()  # the spans: written as it plays, read as it listens
        self._levels = collections.deque(maxlen=max(1, round(KEEP_S / frame_s)))
        self._learn = max(1, round(LEARN_S / frame_s))

    def played(self, seconds, speech=False):
        """grog's sound: `seconds` more of it, its speech or not (an earcon),
        written to a speaker now, which plays it after what it's still
        playing, if anything."""
        now = self.clock()
        with self._lock:
            start = max(now, self._sound[-1][1]) if self._sound else now
            _add(self._sound, start, start + seconds)
            if speech:
                _add(self._speech, start, start + seconds)

    def playing(self, t):
        """Whether grog's sound, or its echo, may have been heard at `t`."""
        with self._lock:
            return any(start <= t <= end + TAIL_S for start, end in self._sound)

    def speaking(self, t):
        """Whether grog's speech was playing at `t`."""
        with self._lock:
            return any(start <= t <= end for start, end in self._speech)

    def gate(self, t, frame, prob):
        """The VAD's speech probability `prob` for `frame`, heard at `t`, or 0
        while grog's sound played, unless the frame was louder than its echo
        (MARGIN_DB over its loudest): the echo's level is learned meanwhile,
        while grog speaks."""
        if not self.playing(t):
            return prob
        level = rms_dbfs(frame)
        if self.speaking(t):
            self._levels.append(level)
            if len(self._levels) >= self._learn:
                ranked = sorted(self._levels)
                self.limit = ranked[min(len(ranked) - 1, len(ranked) * PERCENTILE // 100)] + MARGIN_DB
        return prob if self.limit is not None and level > self.limit else 0.0
