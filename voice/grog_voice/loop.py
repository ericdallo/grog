"""The voice loop: waits for an utterance (see ears.py) and, when it's for
grog ("Grog, ..."), asks grog and speaks the answer as it streams in (see
mouth.py), listening meanwhile: someone calling grog then ("Grog", or
"Grog, ...") cuts the answer off at once, and what they say is answered
instead. While grog's own sound plays, what the mic hears of it isn't
taken for speech (see echo.py): only someone heard over it. grog may also
ask it to listen now, to stop answering, or to turn its mic off, and tells
it when it plays music (see commands.py)."""

import logging
import threading
import time
from dataclasses import dataclass

from .audio import FRAME_BYTES, FRAME_S, MicError
from .ears import Ears, Heard
from .mouth import Mouth, Turn, where

log = logging.getLogger("grog_voice")

# After just the wake word, or a question from grog ("Posso?"), how long what's
# said next needs none.
AWAKE_S = 8.0

# What it tells grog while it answers.
ANSWERING = ("answering", "speaking")

# How often, at most, its sound says grog is back: grog may come and go a few
# times as it restarts.
BACK_EVERY_S = 60.0


@dataclass
class Result:
    """What a turn did: `answered` grog asked `command` (how it went: `turn`),
    `woken` just the wake word, or `ignored` what wasn't for grog. `then`:
    what was heard while grog answered, to take next: someone calling grog,
    which cut the answer off, or what was still being said once it was
    over."""

    kind: str  # answered, woken or ignored
    heard: Heard
    command: str | None = None
    turn: Turn | None = None
    then: Heard | None = None


class Voice:
    """Wires the mic, the models and grog together. `player(rate, speaker)`
    makes a Player for a sample rate, on the speaker grog picked (a name, or
    None: its own), `ask(text, said)` yields grog's answer to a text as it
    streams (("text", text) and ("tool", name)), `wake` gives the WakeWords to
    answer to (None: answers anything), `report` tells grog what it's doing,
    `report_heard(text, final)` what it heard for grog (see reporter.py), and
    `captions` whether grog shows that (its skin.captions): only then is what
    it hears for grog reported, and are its partials made to its end.
    `partial_stt` transcribes partials (None: none, see Ears). `echo` (an
    Echo) keeps grog's own sound from being taken for someone talking (None:
    nothing does); `clock` tells the time, as the mic stamps its frames.
    `ring` (a Ring, or None) is shown what it does and told whether grog's
    mic is on: a ReSpeaker's ring and mute button (see ring.py)."""

    def __init__(
        self,
        settings,
        *,
        mic,
        vad,
        stt,
        tts,
        player,
        ask,
        wake=lambda: None,
        report=lambda state: None,
        report_heard=lambda text, final: None,
        captions=lambda: False,
        partial_stt=None,
        echo=None,
        clock=time.monotonic,
        ring=None,
        frame_bytes=FRAME_BYTES,
        frame_s=FRAME_S,
    ):
        self.report = report
        self.ring = ring
        self.awake_until = 0.0
        self.awake_why = None  # why what's said until then needs no wake word (see awake)
        self.told = None  # what it last told grog it's doing
        self.no_mic = False
        self.muted = False  # its mic is switched off (see set_mic)
        self.music = False  # grog plays music (see set_music)
        self.speaker = None  # the speaker grog picked for it (see set_speaker)
        # How much of the last answer was said before it was cut off, for
        # grog, with what's asked next: None when it wasn't.
        self.cut_off = None
        self._telling = threading.Lock()
        self._back_at = None  # when its sound last said grog is back
        self.mouth = Mouth(
            tts=tts,
            player=lambda rate: player(rate, self.speaker),
            ask=ask,
            tell=self.tell,
            captions=captions,
            played=echo.played if echo is not None else lambda seconds, speech=False: None,
            clock=clock,
        )
        self.ears = Ears(
            settings,
            mic=mic,
            vad=vad,
            stt=stt,
            partial_stt=partial_stt,
            wake=wake,
            awake=self.awake,
            tell=self.tell,
            chime=self.mouth.chime,
            report_heard=report_heard,
            captions=captions,
            music=lambda: self.music,
            echo=echo,
            busy=lambda: self.mouth.state,
            called=self._called,
            frame_bytes=frame_bytes,
            frame_s=frame_s,
        )
        if (watch := getattr(mic, "watch", None)) is not None:
            watch(self._mic)

    def run(self):
        """Turns, forever. Only ends (raising MicError) when the mic's input
        does: a file's. What the mic hears while grog's sounds play is heard
        too, but for their echo (see echo.py): a reply comes quickly
        ("Sim"), and someone may call grog while it answers."""
        log.info("listening")
        then = None
        while True:
            try:
                result = self.turn(then)
            except MicError:
                raise
            except Exception as e:
                log.error("the turn failed: %s", where(e))
                self.tell("error")
                then = None
            else:
                heard, then = result.heard, result.then
                if result.kind == "ignored":
                    self._ignored(heard)
                    continue
                if result.kind == "woken":
                    log.info("just the wake word: %s", self.ears.describe(heard))
                    if heard.chimed:
                        # Its sound played as they spoke: no need to wait
                        # for it.
                        continue
                else:
                    log.info("%s", result.turn)
            self.mouth.quiet()

    def turn(self, heard=None):
        """Listens for an utterance, unless given one (`heard`, heard while
        grog answered), and answers it when it's for grog (see answer), or
        after just the wake word, listens without it for a while (awake), as
        after an answer asking something (asked). Returns once its sounds are
        over, or once someone called grog while it answered."""
        if heard is None:
            heard = self.ears.listen()
        if heard.said:
            self.awake_until = 0.0
        command = self.ears.address(heard)
        if command is None:
            self.mouth.quiet()
            return Result("ignored", heard)
        if not command:
            self.wake_up("awake")
            self.mouth.quiet()
            if not heard.chimed:
                self.mouth.play("listening")
            return Result("woken", heard, command)
        turn, then = self.answer(heard, command)
        return Result("answered", heard, command, turn, then)

    def answer(self, heard, command):
        """Asks grog `command` and speaks its answer, listening meanwhile:
        what isn't for grog is let be, while someone calling grog cuts the
        answer off (see _called), what they say to be answered next. grog
        hears how much of an answer cut off was said, with what's asked
        next. An answer asking something wakes it up (asked): the reply
        needs no wake word, even when it started as grog finished asking.
        Returns the turn, and what was heard while it answered to take next,
        if anything: what was for grog, or what was still being said once
        the answer was over."""
        answering = self.mouth.start(heard, command, self.cut_off)
        self.cut_off = None
        then = None
        try:
            while (later := self.ears.listen(until=answering.done.is_set)) is not None:
                if answering.done.is_set():
                    then = later
                    break
                if self.ears.command(later.said, later.needs_name, later.addressed, later.anywhere) is None:
                    self._ignored(later)
                    continue
                self._called()
                then = later
                break
        except MicError:
            pass  # a file's end, as the mic: the answer goes on, and the next turn ends there
        finally:
            answering.done.wait()
        turn = answering.turn()
        if turn.stopped:
            self.cut_off = turn.said
        elif turn.answer.rstrip().endswith("?"):
            self.wake_up("asked")
            if then is not None:
                then.needs_name = False
        return turn, then

    def _called(self):
        """Someone turned out to talk to grog: what it answers, if anything,
        is cut off at once."""
        if self.mouth.state is not None and not self.mouth.stopping.is_set():
            log.info("called while answering: cutting it off")
            self.mouth.stop()

    def _ignored(self, heard):
        """Logs how what wasn't for grog sounded, to tell a quiet mic from a
        wake word misheard: never what was said."""
        why = "not for grog" if heard.said else "no words heard"
        log.info("%s: %s", why, self.ears.describe(heard))

    def wake_up(self, why):
        """What's said for a while needs no wake word, `why` being awake (its
        name came alone) or asked (its answer asked something)."""
        self.awake_until = time.monotonic() + AWAKE_S
        self.awake_why = why

    def awake(self):
        """Why what's said now needs no wake word, awake or asked (see
        wake_up), or None when it needs one."""
        return self.awake_why if time.monotonic() < self.awake_until else None

    def close(self):
        """Closes the mic, what transcribes partials, and the ring."""
        self.ears.close()
        if self.ring is not None:
            self.ring.close()

    def listen_now(self):
        """Listens to what's said next without its name, as if called, once
        it stopped what it was saying, if anything: its sound says it
        listens."""
        self.mouth.stop()
        self.wake_up("awake")
        self.mouth.chime("listening")

    def back(self):
        """grog answers, at first or again after it was away (it restarted,
        say after an update): its sound says so, unless it played a moment
        ago or grog-voice is answering."""
        now = time.monotonic()
        with self._telling:
            if self.told in ANSWERING or (self._back_at is not None and now - self._back_at < BACK_EVERY_S):
                return
            self._back_at = now
        log.info("grog is back")
        self.mouth.chime("back")

    def stop(self):
        """Stops answering, if it is: what it says is cut off."""
        self.mouth.stop()

    def set_mic(self, on):
        """Switches the mic on or off: off, it records nothing, and grog is
        told it's muted, once it's done answering if it is. What was being
        said is forgotten. The ring is told, even when it was already so."""
        if self.ring is not None:
            self.ring.mic(on)
        if self.muted == (not on):
            return
        self.muted = not on
        if (switch := getattr(self.ears.mic, "switch", None)) is not None:
            switch(on)
        self.ears.forget()
        log.info("mic %s", "on" if on else "off")
        with self._telling:
            answering = self.told in ANSWERING
        if not answering:
            self.tell(self.awake() or "listening")

    def set_music(self, playing):
        """grog plays music, or no longer does: while it does, its name is
        looked for in short windows of what's heard (see Ears._window), and
        from the next frame on once it stops, as usual."""
        if self.music == playing:
            return
        self.music = playing
        log.info("music %s", "on" if playing else "off")

    def set_speaker(self, name):
        """What it says from now on plays on speaker `name`, grog's pick, or
        on its own (its speaker setting, or the default) with None. Gone,
        PipeWire plays it on the default meanwhile."""
        if self.speaker == name:
            return
        self.speaker = name
        log.info("speaking on %s", name or "its own speaker")

    def tell(self, state):
        """Tells grog what it's doing, when that changed: muted, while the
        mic is switched off, or no mic, while there's none, when it would be
        listening."""
        with self._telling:
            if state in ("listening", "awake", "asked"):
                if self.muted:
                    state = "muted"
                elif self.no_mic:
                    state = "no-mic"
            if state != self.told:
                self.told = state
                self.report(state)
                if self.ring is not None:
                    self.ring.show(state)

    def _mic(self, state):
        """What the mic says: no-mic, or listening once it's back."""
        self.no_mic = state == "no-mic"
        self.tell(state)
