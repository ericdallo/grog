"""Fakes of the mic, the models, the speaker and grog, for the tests of the
ears, the mouth and the loop."""

import threading
import time

from grog_voice import sounds
from grog_voice.audio import MicError, Silence
from grog_voice.grog import GrogError

FRAME = 4  # bytes per frame
FRAME_S = 0.032
SPEECH, SILENCE = b"SSSS", b"...."
SETTINGS = {
    "endSilenceMs": 600,
    "speculateMs": 250,
    "partialMs": 0,
    "maxUtteranceSeconds": 15,
    "musicUtteranceSeconds": 4,
    "followUpSeconds": 8,
}
# Partials: what was said so far, transcribed while they talk.
PARTIAL = {**SETTINGS, "partialMs": 300}
HEARD, ANSWER = "que horas são", "São dez e quinze. Mais alguma coisa?"
ACK, LISTENING, ERROR, BACK, ALARM = (
    s(22050) for s in (sounds.ack, sounds.listening, sounds.error, sounds.back, sounds.alarm)
)
# What PausingTts says after a text: a second of sound, then, after a
# sentence, a pause of 0.4 s.
SOUND, PAUSE = b"\x01\x00" * 22050, Silence(2 * 8820)


def frames(*parts, silence=SILENCE):
    """("S", 1.0) is a second of speech, (".", 0.5) half a second of
    `silence`."""
    return [
        SPEECH if kind == "S" else silence for kind, seconds in parts for _ in range(round(seconds / FRAME_S))
    ]


# What the mic hears of grog's own voice, from its speaker: speech to the
# VAD (see FakeVad), at -52 dBFS. And silence as a mic hears it, quiet.
ECHO, HUSH = b"S\x00S\x00", bytes(FRAME)


def quiet(*parts):
    """frames(*parts), its silence quiet (HUSH): what grog's echo is told
    apart from (see echo.py)."""
    return frames(*parts, silence=HUSH)


def seconds(pcm):
    return len(pcm) / FRAME * FRAME_S


def song(seconds, start=0):
    """`seconds` of what the VAD takes for speech, like music, each frame
    numbered from `start` on (see numbered)."""
    return [b"S" + i.to_bytes(3, "big") for i in range(start, start + round(seconds / FRAME_S))]


def numbered(pcm):
    """The numbers of the first and the last frame of song in `pcm`."""
    each = (pcm[i : i + FRAME] for i in range(0, len(pcm), FRAME))
    numbers = [int.from_bytes(frame[1:], "big") for frame in each if frame[:1] == b"S"]
    return numbers[0], numbers[-1]


class OverMusic:
    """A fake speech to text over music: windows (WINDOW long) say
    `windows` in turn, the last one over and over; anything else says
    `rest`, or in turn when it's a list. The windows it heard are in
    .windows, the rest in .others."""

    WINDOW = round(4 / FRAME_S) * FRAME  # musicUtteranceSeconds in SETTINGS

    def __init__(self, windows, rest=""):
        self.texts, self.windows, self.others = list(windows), [], []
        self.rest = [rest] if isinstance(rest, str) else list(rest)

    def __call__(self, pcm):
        if len(pcm) != self.WINDOW:
            self.others.append(pcm)
            return self.rest[min(len(self.others), len(self.rest)) - 1]
        self.windows.append(pcm)
        return self.texts[min(len(self.windows), len(self.texts)) - 1]


class FakeMic:
    """Frames, then MicError, once `drained`. `frame_wait_s` between them;
    `gate`, an Event the frames wait for from frame `gate_at` on; none comes
    while `hold()` says so (up to 5 s). How it was switched on and off is in
    .switched."""

    def __init__(self, frames, frame_wait_s=0, gate=None, gate_at=0):
        self.frames = list(frames)
        self.frame_wait_s, self.gate, self.gate_at = frame_wait_s, gate, gate_at
        self.hold = lambda: False
        self.drained = threading.Event()
        self.flushed, self.read_count, self.closed = 0, 0, False
        self.switched = []

    def switch(self, on):
        self.switched.append(on)

    def read(self, timeout=None):
        if not self.frames:
            self.drained.set()
            raise MicError("no more frames")
        if self.gate is not None and self.read_count >= self.gate_at:
            self.gate.wait(5)
        deadline = time.monotonic() + 5
        while self.hold() and time.monotonic() < deadline:
            time.sleep(0.001)
        time.sleep(self.frame_wait_s)
        self.read_count += 1
        return time.monotonic(), self.frames.pop(0)

    def flush(self):
        self.flushed += 1

    def close(self):
        self.closed = True


def slow_mic(*parts):
    """A FakeMic of frames(*parts), 2 ms a frame: partials, and what's
    transcribed at a pause, are done before the utterance ends."""
    return FakeMic(frames(*parts), frame_wait_s=0.002)


class SimMic(FakeMic):
    """A FakeMic stamping its frames with a time of its own, FRAME_S on for
    each, as a mic records in real time (2 ms a frame here): `clock()`
    tells it, for what compares it with frames (see echo.py)."""

    def __init__(self, frames, **kwargs):
        super().__init__(frames, **({"frame_wait_s": 0.002} | kwargs))
        self.now = 1000.0

    def clock(self):
        return self.now

    def read(self, timeout=None):
        _, frame = super().read(timeout)
        self.now += FRAME_S
        return self.now, frame


class FakeVad:
    """Speech: frames starting with S (SPEECH, or song's)."""

    def __call__(self, frame):
        return 0.9 if frame[:1] == b"S" else 0.1

    def reset(self):
        pass


class FakeStt:
    """Says `texts` in turn, the last one over and over: an exception is
    raised."""

    def __init__(self, *texts):
        self.texts, self.calls = list(texts), []

    def __call__(self, pcm):
        self.calls.append(pcm)
        text = self.texts[min(len(self.calls), len(self.texts)) - 1]
        if isinstance(text, Exception):
            raise text
        return text


class Saying:
    """A fake speech to text of someone saying `text`, a word every
    `per_word_s` of speech: what they said so far, in partials."""

    def __init__(self, text, per_word_s=0.25):
        self.words, self.per_word_s, self.calls = text.split(), per_word_s, []

    def __call__(self, pcm):
        self.calls.append(pcm)
        return " ".join(self.words[: int(pcm.count(SPEECH) * FRAME_S / self.per_word_s)])


class FakeTts:
    sample_rate = 22050

    def __init__(self):
        self.texts = []

    def __call__(self, text):
        self.texts.append(text)
        yield text.encode()


class StreamingTts(FakeTts):
    """A FakeTts whose speech plays as it's made, like Pocket TTS. Its first
    text sets `started`, then waits for `go`."""

    streams = True

    def __init__(self, started, go):
        super().__init__()
        self.started, self.go = started, go

    def __call__(self, text):
        if not self.texts:
            self.started.set()
            self.go.wait(2)
        yield from super().__call__(text)


class PausingTts(StreamingTts):
    """A StreamingTts that says a second of sound (SOUND) after each text
    and, when it ends a sentence, pauses (PAUSE), like Pocket TTS."""

    def __call__(self, text):
        yield from super().__call__(text)
        yield SOUND
        if text.endswith((".", "!", "?")):
            yield PAUSE


class Players:
    """Makes FakePlayers, keeping them. `failing`: their writes fail.
    `one_closed` once one of them was."""

    def __init__(self, failing=False):
        self.made, self.failing, self.one_closed = [], failing, threading.Event()

    def __call__(self, rate, speaker=None):
        player = FakePlayer(rate, self.failing, self.one_closed, speaker)
        self.made.append(player)
        return player


class FakePlayer:
    def __init__(self, rate, failing=False, on_close=None, speaker=None):
        self.rate, self.written, self.closed, self.failing = rate, [], False, failing
        self.speaker = speaker
        self.stopped = False
        self.on_close = on_close or threading.Event()

    def write(self, pcm):
        if self.failing or self.stopped:
            raise BrokenPipeError("pw-cat is gone")
        self.written.append(pcm)

    def stop(self):
        self.stopped = True

    def close(self):
        self.closed = True
        self.on_close.set()


def played(players):
    """All the players played, in order."""
    return [pcm for player in players.made for pcm in player.written]


def answering(*pieces):
    """A fake grog answering `pieces`: text, ("tool", name) or an exception
    to raise. It keeps what it was asked in .asked, what was said in .said,
    and how much of its last answer was said before it was cut off in
    .cut_offs."""

    def ask(text, said=None, cut_off=None, on_connect=None):
        ask.asked.append(text)
        ask.said.append(said)
        ask.cut_offs.append(cut_off)
        for piece in pieces:
            if isinstance(piece, Exception):
                raise piece
            yield piece if isinstance(piece, tuple) else ("text", piece)

    ask.asked, ask.said, ask.cut_offs = [], [], []
    return ask


def answers(*asks):
    """A fake grog answering each time as the next of `asks` (the last one
    over and over), keeping what it was asked in .asked and how much of its
    last answer was said before it was cut off in .cut_offs."""

    def ask(text, said=None, cut_off=None, on_connect=None):
        ask.asked.append(text)
        ask.cut_offs.append(cut_off)
        return asks[min(len(ask.asked), len(asks)) - 1](text, said, cut_off, on_connect)

    ask.asked, ask.cut_offs = [], []
    return ask


class OneSecond(FakeTts):
    """A FakeTts saying each text in a second (SOUND)."""

    def __call__(self, text):
        self.texts.append(text)
        yield SOUND


class Speaking(Players):
    """Players playing as grog speaks: its earcons and an answer's first
    second (SOUND) at once, then, the first time, its next second until
    stopped or `done` is set, `speaking` meanwhile."""

    def __init__(self):
        super().__init__()
        self.speaking, self.done = threading.Event(), threading.Event()

    def __call__(self, rate, speaker=None):
        player = super().__call__(rate, speaker)
        write = player.write

        def playing(pcm):
            write(pcm)
            if pcm != SOUND or player.written.count(SOUND) < 2 or self.speaking.is_set():
                return
            self.speaking.set()
            deadline = time.monotonic() + 5
            while not (player.stopped or self.done.is_set()) and time.monotonic() < deadline:
                time.sleep(0.002)
            if player.stopped:
                raise BrokenPipeError("pw-cat was killed")

        player.write = playing
        return player


class FakeConnection:
    """A connection to grog, as grog.ask gives it: `hung_up` once its socket
    was shut down."""

    def __init__(self):
        self.hung_up = threading.Event()
        self.sock = self

    def shutdown(self, how):
        self.hung_up.set()


def hanging(*pieces):
    """A fake grog answering `pieces`, then nothing more until it's hung up
    on (see FakeConnection): it stops answering then. `.answered` once it
    gave them all."""
    conn, answered = FakeConnection(), threading.Event()

    def ask(text, said=None, cut_off=None, on_connect=None):
        if on_connect is not None:
            on_connect(conn)
        yield from (("text", piece) for piece in pieces)
        answered.set()
        if not conn.hung_up.wait(5):
            raise AssertionError("never hung up on")
        raise GrogError("grog stopped answering: the connection was reset")

    ask.conn, ask.answered = conn, answered
    return ask


class Held:
    """A fake speech to text of partials, in a process of its own (see
    worker.py): `stt`, keeping how many hold it."""

    def __init__(self, stt):
        self.stt, self.holds, self.closed = stt, 0, False
        self._lock = threading.Lock()

    def __call__(self, pcm):
        return self.stt(pcm)

    def hold(self):
        with self._lock:
            self.holds += 1

    def release(self):
        with self._lock:
            self.holds -= 1

    def close(self):
        self.closed = True
