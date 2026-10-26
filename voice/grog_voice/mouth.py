"""grog-voice's mouth: asks grog what was heard for it and speaks the answer
as it streams in, a chunk at a time, on a thread of its own, and plays its
earcons (see sounds.py). Asked to stop, it cuts the answer off, knowing how
much of it was said."""

import logging
import queue
import re
import socket
import threading
import time
import traceback
from dataclasses import dataclass, field

from . import sounds
from .audio import Silence, sounded
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
    unsaid: bool = False  # its text to speech failed: the rest went unsaid
    answer: str = field(default="", repr=False)
    # What of the answer was said aloud, its sentences said to their end
    # before it was cut off; all of it when it wasn't.
    said: str = field(default="", repr=False)

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
            f"{'; unsaid' if self.unsaid else '; stopped' if self.stopped else ''}"
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


# Exclamation marks next to another sentence mark, which they only make
# more excited ("Sério?!"), and Spanish's opening one.
_EXCLAIMED = re.compile(r"(?<=[?.…])[!¡]+|[!¡]+(?=[?.…])|¡")


def calmed(text):
    """`text` with its exclamations said as statements, "Claro!" as "Claro."
    and "Sério?!" as "Sério?": they make text to speech sound too cheerful
    (the user, of every voice)."""
    return re.sub(r"!+", ".", _EXCLAIMED.sub("", text))


class Pauses:
    """Plays the pauses that text to speech puts after sentences (Silence)
    only before more sound, and only what's left of them by then, its sound
    playing as it comes: an answer doesn't end with one, so the mic opens as
    soon as it's over, and one doesn't add to a wait for more (grog using a
    tool, say). `rate` is the sound's sample rate; `clock` tells the time, in
    seconds."""

    def __init__(self, rate, clock=time.monotonic):
        self.rate, self.clock = rate, clock
        self.over = clock()  # when the sound played so far is over
        self.held = 0  # samples of a pause, until more sound comes

    def __call__(self, pcm):
        """What to play for `pcm`, now: nothing for a pause, held; otherwise
        what's left of the pause held, if anything, then `pcm`."""
        if isinstance(pcm, Silence):
            self.held += len(pcm) // 2
            return []
        now = self.clock()
        left = int(self.held - max(0.0, now - self.over) * self.rate)
        self.held = 0
        sound = [bytes(2 * left), pcm] if left > 0 else [pcm]
        self.over = max(self.over, now) + sum(map(len, sound)) / 2 / self.rate
        return sound


# Speech made slower than it plays (text to speech falling behind, as on a
# CPU too hot to keep up) ran out every 80 ms piece: a gap each time, heard
# as crackling. Once it ran out mid-chunk, the sound that follows waits until
# there's this much of it, then plays: a pause now and then instead
# (plans/voice.md, "Long answers").
CUSHION_S = 1.0


class Cushion:
    """Holds sound back once it came after the sound before it was over,
    until there's `seconds` of it (see CUSHION_S): a chunk's first sound goes
    at once (see restart). `rate` is the sound's sample rate; `clock` tells
    the time, in seconds."""

    def __init__(self, rate, clock=time.monotonic, seconds=CUSHION_S):
        self.rate, self.clock, self.seconds = rate, clock, seconds
        self.over = None  # when the sound let through since the chunk started is over
        self.held = []

    def __call__(self, pcm):
        """What to play for `pcm` now: nothing while it's held."""
        if not self.held and (self.over is None or self.clock() <= self.over):
            return self._through([pcm])
        self.held.append(pcm)
        return self.flush() if self._seconds(self.held) >= self.seconds else []

    def flush(self):
        """What it holds, to play now: at a chunk's end, however little."""
        held, self.held = self.held, []
        return self._through(held)

    def restart(self):
        """Another chunk starts: its first sound isn't held."""
        self.over = None

    def _through(self, sound):
        if sound:
            now = self.clock()
            self.over = (now if self.over is None else max(self.over, now)) + self._seconds(sound)
        return sound

    def _seconds(self, sound):
        return sum(map(len, sound)) / 2 / self.rate


class Spoken(bytes):
    """16-bit audio of grog's speech, rather than an earcon or a pause: what
    its echo is learned from (see echo.py)."""


def where(e):
    """The type of an exception and where it happened, without its message
    (which could quote what was said)."""
    frames = traceback.extract_tb(e.__traceback__)
    return f"{type(e).__name__} at {frames[-1].filename}:{frames[-1].lineno}" if frames else type(e).__name__


class Answering:
    """An answer under way, on a thread of its own running `answer()`:
    `done` once it's over, and its Turn (`turn()`, which waits for it)."""

    def __init__(self, answer):
        self.done = threading.Event()
        self._turn = self._error = None

        def run():
            try:
                self._turn = answer()
            except BaseException as e:  # raised by turn()
                self._error = e
            finally:
                self.done.set()

        threading.Thread(target=run, daemon=True, name="answer").start()

    def turn(self):
        self.done.wait()
        if self._error is not None:
            raise self._error
        return self._turn


class Mouth:
    """Speaks with `tts`, on players that `player` makes for a sample rate.
    `ask(text, said, cut_off, on_connect)` yields grog's answer to a text as
    it streams (("text", text) and ("tool", name)), `said` being what was
    said aloud as heard when grog may see it (None otherwise), `cut_off` how
    much of grog's last answer was said aloud before it was cut off, if it
    was (None otherwise), `on_connect` getting the connection to grog (see
    grog.ask), to hang up when it stops; `tell` tells grog what it's doing,
    `captions` whether grog shows what's said (its skin.captions), and
    `played(seconds, speech)` is told of its sound as it's written to a
    speaker, its speech or not (see echo.py); `clock` tells the time, in
    seconds. `state`: what it does, answering or speaking, while it answers;
    None otherwise."""

    def __init__(
        self,
        *,
        tts,
        player,
        ask,
        tell=lambda state: None,
        captions=lambda: False,
        played=lambda seconds, speech=False: None,
        clock=time.monotonic,
    ):
        self.tts, self.player, self.ask, self.tell, self.captions = tts, player, ask, tell, captions
        self.played, self.clock = played, clock
        # Its speech plays as it's made (Pocket TTS): it's given whole
        # sentences (see chunks.py), together when several are waiting, as
        # it reads a whole text better than its sentences one by one.
        self.streams = getattr(tts, "streams", False)
        self.chiming = None  # the thread playing its sounds while it listens
        self.stopping = threading.Event()  # the answer under way is to stop
        self.state = None
        self._stopped_at = None  # when the answer under way was asked to stop
        self._conn = None  # to grog, while it answers
        self._playing = None  # the player of the answer under way

    @property
    def sample_rate(self):
        return self.tts.sample_rate

    def sound(self, name):
        return SOUNDS[name](self.sample_rate)

    def answer(self, heard, command, cut_off=None):
        """Answers `command`, returning the Turn once it's over (see start)."""
        return self.start(heard, command, cut_off).turn()

    def start(self, heard, command, cut_off=None):
        """Asks grog `command` and speaks the answer as it comes, a chunk at a
        time, after an earcon saying it got it (unless that played as it
        ended), and another when grog can't answer, on a thread of its own:
        an Answering. Its tools (like web search) have none: the ack just
        played, and a sound right after it would only confuse. Asked to stop
        meanwhile (see stop), it cuts it off, without a sound. `cut_off`:
        how much of grog's last answer was said before it was cut off, if it
        was."""
        self.stopping.clear()
        self._stopped_at = None
        self.state = "answering"
        self.tell("answering")
        return Answering(lambda: self._answer(heard, command, cut_off))

    def _answer(self, heard, command, cut_off):
        try:
            return self._answering(heard, command, cut_off)
        finally:
            self.state = None

    def _answering(self, heard, command, cut_off):
        turn = Turn(heard.speech_end, heard.seconds, heard.peak_db, heard.speculated)
        turn.marks["transcript"] = heard.transcribed
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
            answer = self.ask(command, said, cut_off=cut_off, on_connect=connected)
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
        # Cut off, as far as grog is concerned, when it went unsaid: grog
        # hears how much of it was said (see loop.Voice.answer).
        turn.stopped = self.stopping.is_set() or turn.unsaid
        if failure and not turn.stopped:
            e = failure[0]
            log.error("grog couldn't answer: %s", e if isinstance(e, GrogError) else where(e))
            self.tell("error")
        elif turn.unsaid:
            self.tell("error")
        return turn

    def stop(self):
        """Stops the answer under way, if any, at once: what's playing is
        cut off, and grog's turn ends, as it's hung up on."""
        if not self.stopping.is_set():
            self._stopped_at = self.clock()
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
            self.played(self._seconds(pcm))
            player.write(pcm)
        finally:
            player.close()

    def _seconds(self, pcm):
        return len(pcm) / 2 / self.sample_rate

    def _speak(self, chunks, turn):
        """Plays the queue's earcons ((name, pcm)) and synthesizes its text
        chunks as they come (None ends them), calmed, while a thread plays
        them, with the pauses after their sentences in between (see Pauses),
        held back once they're made slower than they play (see Cushion).
        Once that failed, or it's to stop, it only waits for the end. Once
        text to speech failed, what it made plays, then an earcon, the rest
        unsaid (the turn's unsaid), grog hung up on. What of it was said,
        once it's over, is in the turn: the chunks whose sound was due to end
        before it was asked to stop."""
        audio, failed, pauses = queue.Queue(), threading.Event(), Pauses(self.sample_rate)
        cushion = Cushion(self.sample_rate, self.clock)
        said = []  # (a chunk, when its sound is due to end), as they're played

        def over():
            return failed.is_set() or turn.unsaid or self.stopping.is_set()

        def play():
            player, due, cut = None, 0.0, False
            try:
                while (pcm := audio.get()) is not None:
                    if isinstance(pcm, str):  # that chunk's sound, all of it
                        if not cut:
                            said.append((pcm, due))
                        continue
                    if self.stopping.is_set():
                        cut = True
                        continue
                    if player is None:
                        player = self._playing = self.player(self.sample_rate)
                        if self.stopping.is_set():
                            player.stop()
                    due = max(self.clock(), due) + self._seconds(pcm)
                    self.played(self._seconds(pcm), isinstance(pcm, Spoken))
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
                    for sound in pauses(pcm):
                        audio.put(sound)
                    continue
                if self.streams:
                    item = gathered(item, chunks, ahead)
                cushion.restart()
                try:
                    for pcm in self.tts(calmed(item)):
                        if over():
                            break
                        for sound in pauses(pcm if isinstance(pcm, Silence) else Spoken(pcm)):
                            for held in cushion(sound):
                                if "first_audio" not in turn.marks:
                                    turn.mark("first_audio")
                                    self.state = "speaking"
                                    self.tell("speaking")
                                audio.put(held)
                    else:
                        for held in cushion.flush():
                            audio.put(held)
                        audio.put(item)
                except Exception as e:
                    # What was made of it plays, and of the rest nothing: grog,
                    # hung up on, keeps what was said (see _answering).
                    log.error("speaking failed: %s", where(e))
                    turn.unsaid = True
                    self._hang_up()
        finally:
            audio.put(None)
            playing.join()
        if turn.unsaid:
            turn.sounds.append("error")
            self.play("error")
        stopped_at = self._stopped_at if self.stopping.is_set() else None
        turn.said = " ".join(chunk for chunk, due in said if stopped_at is None or due <= stopped_at)
        turn.mark("done")
