"""grog-voice's mouth: asks grog what was heard for it and speaks the answer
as it streams in, a chunk at a time, and plays its earcons (see sounds.py).
Asked to stop, it cuts the answer off."""

import logging
import queue
import socket
import threading
import time
import traceback
from dataclasses import dataclass, field

from . import sounds
from .audio import sounded
from .chunks import Chunker
from .grog import GrogError

log = logging.getLogger("grog_voice")

# Its earcons, by name.
SOUNDS = {
    "ack": sounds.ack,
    "listening": sounds.listening,
    "error": sounds.error,
    "back": sounds.back,
}

# What a turn's line says of its marks, in the order they came.
_MARKS = {
    "ack": "got it",
    "transcript": "transcript",
    "first_text": "first words",
    "first_audio": "speaking",
    "done": "done",
}


@dataclass
class Turn:
    """How a turn went. Its str, for the log, has timings and the tools used:
    never what was said."""

    speech_end: float
    speech_s: float
    peak_db: float
    speculated: bool
    marks: dict = field(default_factory=dict)
    tools: list = field(default_factory=list)
    sounds: list = field(default_factory=list)  # the earcons played, in order
    stopped: bool = False  # it was asked to stop, and cut the answer off
    answer: str = field(default="", repr=False)

    def mark(self, name):
        self.marks.setdefault(name, time.monotonic())

    def __str__(self):
        since = ", ".join(
            f"{_MARKS[key]} {at - self.speech_end:.2f} s"
            for key, at in sorted(self.marks.items(), key=lambda mark: mark[1])
            if key in _MARKS
        )
        return (
            f"turn: {self.speech_s:.1f} s of speech ({sounded(self.peak_db, self.speculated)});"
            f" since it ended: {since}{'; tools: ' + ', '.join(self.tools) if self.tools else ''}"
            f"{'; stopped' if self.stopped else ''}"
        )


def gathered(text, chunks, ahead):
    """`text`, with the text chunks already waiting after it in the queue
    `chunks`, up to the first item that isn't text (an earcon, or the end),
    which goes to `ahead`."""
    while True:
        try:
            item = chunks.get_nowait()
        except queue.Empty:
            return text
        if not isinstance(item, str):
            ahead.append(item)
            return text
        text = f"{text} {item}"


def where(e):
    """The type of an exception and where it happened, without its message
    (which could quote what was said)."""
    frames = traceback.extract_tb(e.__traceback__)
    return f"{type(e).__name__} at {frames[-1].filename}:{frames[-1].lineno}" if frames else type(e).__name__


class Mouth:
    """Speaks with `tts`, on players that `player` makes for a sample rate.
    `ask(text, said, on_connect)` yields grog's answer to a text as it
    streams (("text", text) and ("tool", name)), `said` being what was said
    aloud as heard when grog may see it (None otherwise), `on_connect`
    getting the connection to grog (see grog.ask), to hang up when it stops;
    `tell` tells grog what it's doing, and `captions` whether grog shows
    what's said (its skin.captions)."""

    def __init__(self, *, tts, player, ask, tell=lambda state: None, captions=lambda: False):
        self.tts, self.player, self.ask, self.tell, self.captions = tts, player, ask, tell, captions
        # Its speech plays as it's made (Pocket TTS): it's given whole
        # sentences (see chunks.py), together when several are waiting, as
        # it reads a whole text better than its sentences one by one.
        self.streams = getattr(tts, "streams", False)
        self.chiming = None  # the thread playing its sounds while it listens
        self.stopping = threading.Event()  # the answer under way is to stop
        self._conn = None  # to grog, while it answers
        self._playing = None  # the player of the answer under way

    @property
    def sample_rate(self):
        return self.tts.sample_rate

    def sound(self, name):
        return SOUNDS[name](self.sample_rate)

    def answer(self, heard, command):
        """Asks grog `command` and speaks the answer as it comes, a chunk at a
        time, after an earcon saying it got it (unless that played as it
        ended), and another when grog can't answer. Its tools (like web
        search) have none: the ack just played, and a sound right after it
        would only confuse. Asked to stop meanwhile (see stop), it
        cuts it off, without a sound."""
        self.stopping.clear()
        turn = Turn(heard.speech_end, heard.seconds, heard.peak_db, heard.speculated)
        turn.marks["transcript"] = heard.transcribed
        self.tell("answering")
        said = heard.said if self.captions() else None
        chunks, failure = queue.Queue(), []
        if heard.acked is None:
            chunks.put(("ack", self.sound("ack")))
        else:
            turn.marks["ack"] = heard.acked
            turn.sounds.append("ack")

        def connected(conn):
            self._conn = conn
            if self.stopping.is_set():
                self._hang_up()

        def produce():
            chunker = Chunker(streams=self.streams)
            answer = self.ask(command, said, on_connect=connected)
            try:
                for kind, value in answer:
                    if self.stopping.is_set():
                        break
                    if kind == "tool":
                        turn.tools.append(value)
                        # What grog said before it ("Let me check..."), said
                        # now rather than after the search.
                        for chunk in chunker.flush():
                            chunks.put(chunk)
                        continue
                    turn.mark("first_text")
                    turn.answer += value
                    for chunk in chunker.add(value):
                        chunks.put(chunk)
            except Exception as e:  # GrogError, or a bug: beep rather than hang
                failure.append(e)
            finally:
                answer.close()
            for chunk in chunker.flush():
                chunks.put(chunk)
            if failure and not self.stopping.is_set():
                chunks.put(("error", self.sound("error")))
            chunks.put(None)

        threading.Thread(target=produce, daemon=True, name="grog").start()
        self.quiet()
        self._speak(chunks, turn)
        self._conn = None
        turn.stopped = self.stopping.is_set()
        if failure and not turn.stopped:
            e = failure[0]
            log.error("grog couldn't answer: %s", e if isinstance(e, GrogError) else where(e))
            self.tell("error")
        return turn

    def stop(self):
        """Stops the answer under way, if any, at once: what's playing is
        cut off, and grog's turn ends, as it's hung up on."""
        self.stopping.set()
        if (player := self._playing) is not None:
            player.stop()
        self._hang_up()

    def _hang_up(self):
        """Hangs up on grog, while it answers: its answer stops coming."""
        sock = getattr(self._conn, "sock", None)
        if sock is not None:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

    def chime(self, name):
        """Plays an earcon on a thread, after the one playing if any, while it
        listens on."""
        before, pcm = self.chiming, self.sound(name)

        def play():
            if before is not None:
                before.join()
            try:
                self._play(pcm)
            except Exception as e:
                log.error("playing failed: %s", where(e))

        self.chiming = threading.Thread(target=play, daemon=True, name="chime")
        self.chiming.start()

    def quiet(self):
        """Waits for the sounds it plays while listening to end."""
        if self.chiming is not None:
            self.chiming.join()
            self.chiming = None

    def play(self, name):
        """Plays an earcon now, returning once it's over."""
        self._play(self.sound(name))

    def _play(self, pcm):
        player = self.player(self.sample_rate)
        try:
            player.write(pcm)
        finally:
            player.close()

    def _speak(self, chunks, turn):
        """Plays the queue's earcons ((name, pcm)) and synthesizes its text
        chunks as they come (None ends them), while a thread plays them. Once
        that failed, or it's to stop, it only waits for the end."""
        audio, failed = queue.Queue(), threading.Event()

        def over():
            return failed.is_set() or self.stopping.is_set()

        def play():
            player = None
            try:
                while (pcm := audio.get()) is not None:
                    if self.stopping.is_set():
                        continue
                    if player is None:
                        player = self._playing = self.player(self.sample_rate)
                        if self.stopping.is_set():
                            player.stop()
                    player.write(pcm)
            except Exception as e:
                failed.set()
                if not self.stopping.is_set():
                    log.error("playing failed: %s", where(e))
            finally:
                self._playing = None
                if player is not None:
                    player.close()

        playing = threading.Thread(target=play, daemon=True, name="player")
        playing.start()
        ahead = []  # taken from the queue while gathering text, still to go
        try:
            while (item := ahead.pop(0) if ahead else chunks.get()) is not None:
                if over():
                    continue
                if isinstance(item, tuple):
                    name, pcm = item
                    turn.sounds.append(name)
                    if name == "ack":
                        turn.mark("ack")
                    audio.put(pcm)
                    continue
                if self.streams:
                    item = gathered(item, chunks, ahead)
                for pcm in self.tts(item):
                    if over():
                        break
                    if "first_audio" not in turn.marks:
                        turn.mark("first_audio")
                        self.tell("speaking")
                    audio.put(pcm)
        finally:
            audio.put(None)
            playing.join()
        turn.mark("done")
