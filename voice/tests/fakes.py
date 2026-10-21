"""Fakes of the mic, the models, the speaker and grog, for the tests of the
ears, the mouth and the loop."""

import threading
import time

from grog_voice import sounds
from grog_voice.audio import MicError
from grog_voice.grog import GrogError

FRAME = 4  # bytes per frame
FRAME_S = 0.032
SPEECH, SILENCE = b"SSSS", b"...."
SETTINGS = {"endSilenceMs": 600, "speculateMs": 250, "partialMs": 0, "maxUtteranceSeconds": 15}
# Partials: what was said so far, transcribed while they talk.
PARTIAL = {**SETTINGS, "partialMs": 300}
HEARD, ANSWER = "que horas são", "São dez e quinze. Mais alguma coisa?"
ACK, LISTENING, SEARCHING, ERROR, BACK = (
    s(22050) for s in (sounds.ack, sounds.listening, sounds.searching, sounds.error, sounds.back)
)


def frames(*parts):
    """("S", 1.0) is a second of speech, (".", 0.5) half a second of silence."""
    return [
        SPEECH if kind == "S" else SILENCE for kind, seconds in parts for _ in range(round(seconds / FRAME_S))
    ]


def seconds(pcm):
    return len(pcm) / FRAME * FRAME_S


class FakeMic:
    """Frames, then MicError. `frame_wait_s` between them; `gate`, an Event
    the frames wait for from frame `gate_at` on. How it was switched on and
    off is in .switched."""

    def __init__(self, frames, frame_wait_s=0, gate=None, gate_at=0):
        self.frames = list(frames)
        self.frame_wait_s, self.gate, self.gate_at = frame_wait_s, gate, gate_at
        self.flushed, self.read_count, self.closed = 0, 0, False
        self.switched = []

    def switch(self, on):
        self.switched.append(on)

    def read(self):
        if not self.frames:
            raise MicError("no more frames")
        if self.gate is not None and self.read_count >= self.gate_at:
            self.gate.wait(5)
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


class FakeVad:
    def __call__(self, frame):
        return 0.9 if frame == SPEECH else 0.1

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


class Players:
    """Makes FakePlayers, keeping them. `failing`: their writes fail.
    `one_closed` once one of them was."""

    def __init__(self, failing=False):
        self.made, self.failing, self.one_closed = [], failing, threading.Event()

    def __call__(self, rate):
        player = FakePlayer(rate, self.failing, self.one_closed)
        self.made.append(player)
        return player


class FakePlayer:
    def __init__(self, rate, failing=False, on_close=None):
        self.rate, self.written, self.closed, self.failing = rate, [], False, failing
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
    to raise. It keeps what it was asked in .asked, and what was said in
    .said."""

    def ask(text, said=None, on_connect=None):
        ask.asked.append(text)
        ask.said.append(said)
        for piece in pieces:
            if isinstance(piece, Exception):
                raise piece
            yield piece if isinstance(piece, tuple) else ("text", piece)

    ask.asked, ask.said = [], []
    return ask


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

    def ask(text, said=None, on_connect=None):
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
