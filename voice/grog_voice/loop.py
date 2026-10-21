"""The voice loop: waits for an utterance (see ears.py) and, when it's for
grog ("Grog, ..."), asks grog and speaks the answer as it streams in (see
mouth.py). Half duplex: what the mic hears while grog answers is dropped
(hearing someone over grog's own voice would take echo cancellation). grog
may also ask it to listen now, to stop answering, or to turn its mic off
(see commands.py)."""

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


@dataclass
class Result:
    """What a turn did: `answered` grog asked `command` (how it went: `turn`),
    `woken` just the wake word, or `ignored` what wasn't for grog."""

    kind: str  # answered, woken or ignored
    heard: Heard
    command: str | None = None
    turn: Turn | None = None


class Voice:
    """Wires the mic, the models and grog together. `player` makes a Player
    for a sample rate, `ask(text, said)` yields grog's answer to a text as it
    streams (("text", text) and ("tool", name)), `wake` gives the WakeWords to
    answer to (None: answers anything), `report` tells grog what it's doing,
    `report_heard(text, final)` what it heard for grog (see reporter.py), and
    `captions` whether grog shows that (its skin.captions): only then is what
    it hears for grog reported, and are its partials made to its end.
    `partial_stt` transcribes partials (None: none, see Ears)."""

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
        frame_bytes=FRAME_BYTES,
        frame_s=FRAME_S,
    ):
        self.report = report
        self.awake_until = 0.0
        self.awake_why = None  # why what's said until then needs no wake word (see awake)
        self.told = None  # what it last told grog it's doing
        self.no_mic = False
        self.muted = False  # its mic is switched off (see set_mic)
        self._telling = threading.Lock()
        self.mouth = Mouth(tts=tts, player=player, ask=ask, tell=self.tell, captions=captions)
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
            frame_bytes=frame_bytes,
            frame_s=frame_s,
        )
        if (watch := getattr(mic, "watch", None)) is not None:
            watch(self._mic)

    def run(self):
        """Turns, forever. Only ends (raising MicError) when the mic's input
        does: a file's."""
        log.info("listening")
        while True:
            try:
                result = self.turn()
            except MicError:
                raise
            except Exception as e:
                log.error("the turn failed: %s", where(e))
                self.tell("error")
            else:
                # The log says how what it ignored sounded, to tell a quiet
                # mic from a wake word misheard.
                heard = result.heard
                if result.kind == "ignored":
                    why = "not for grog" if heard.said else "no words heard"
                    log.info("%s: %s", why, self.ears.describe(heard))
                    continue
                if result.kind == "woken":
                    log.info("just the wake word: %s", self.ears.describe(heard))
                    if heard.chimed:
                        # Its sound played as they spoke: what they say
                        # next, right away maybe, is kept.
                        continue
                else:
                    log.info("%s", result.turn)
            # What the mic heard while grog spoke, or chimed, is dropped, and
            # nothing after: a player returns once its sound is over, with
            # 0.2 s of silence after it for the room's echo (see Player). A
            # reply comes quickly ("Sim").
            self.mouth.quiet()
            self.ears.flush()

    def turn(self):
        """Listens for an utterance and answers it when it's for grog, or
        after just the wake word, listens without it for a while (awake), as
        after an answer asking something (asked). Returns once its sounds are
        over."""
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
        turn = self.mouth.answer(heard, command)
        if turn.answer.rstrip().endswith("?") and not turn.stopped:
            # It asked something: the reply needs no wake word.
            self.wake_up("asked")
        return Result("answered", heard, command, turn)

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
        """Closes the mic, and what transcribes partials."""
        self.ears.close()

    def listen_now(self):
        """Listens to what's said next without its name, as if called, once
        it stopped what it was saying, if anything: its sound says it
        listens."""
        self.mouth.stop()
        self.wake_up("awake")
        self.mouth.chime("listening")

    def stop(self):
        """Stops answering, if it is: what it says is cut off."""
        self.mouth.stop()

    def set_mic(self, on):
        """Switches the mic on or off: off, it records nothing, and grog is
        told it's muted, once it's done answering if it is. What was being
        said is forgotten."""
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

    def _mic(self, state):
        """What the mic says: no-mic, or listening once it's back."""
        self.no_mic = state == "no-mic"
        self.tell(state)
