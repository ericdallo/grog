"""grog-voice's ears: wait for an utterance and transcribe it, at its last
pause in case that's the end, and while someone talks, what they said so far
(partials): to hear grog's name before they're done, and to show their words
as they come. They decide whether it was for grog, never what to answer."""

import threading
import time
from dataclasses import dataclass

from .audio import FRAME_BYTES, FRAME_S, duration, peak_dbfs, sounded
from .endpoint import End, Endpointer, Pause, Resume

# Partials start once what was said so far is this long, the bit before
# speech included.
PARTIAL_FROM_S = 0.8
# While it isn't known to be for grog, only this far: its name comes first.
# The pause and the end still tell.
WAKE_WINDOW_S = 2.5


class Transcription:
    """Speech to text of `pcm`, on a thread, calling `then` once done: a guess
    at what `utterance` says so far, at a pause (`at_pause`) or while they
    talk (a partial), or all of it. `seen` once what it made of it was taken in."""

    def __init__(self, stt, pcm, utterance, at_pause=False, then=lambda: None):
        self.pcm, self.utterance, self.at_pause = pcm, utterance, at_pause
        self.seen = False
        self._text = self._error = None
        self._thread = threading.Thread(target=self._run, args=(stt, then), daemon=True, name="stt")
        self._thread.start()

    def _run(self, stt, then):
        try:
            self._text = stt(self.pcm)
        except Exception as e:  # re-raised by result()
            self._error = e
        finally:
            then()

    def done(self):
        return not self._thread.is_alive()

    def wait(self):
        self._thread.join()

    def result(self):
        self.wait()
        if self._error:
            raise self._error
        return self._text

    def text(self):
        """The text once done, or None when it failed (result() says why)."""
        return None if self._error else self._text


@dataclass
class Utterance:
    """What the guesses made of the utterance under way so far."""

    needs_name: bool  # it has to start with grog's name: not right after just that, or its question
    addressed: bool = False  # a guess showed it's for grog
    asks: bool = False  # the last guess asks grog something, more than its name
    guess: str = ""  # the text of the last guess, once one showed it's for grog
    upto: int = 0  # how much of it that guess heard (bytes)
    chimed: bool = False  # its name got its sound as it went on
    acked: float | None = None  # when the sound saying it got it started, if it did as it ended

    def for_grog(self):
        """Whether someone is known to talk to grog."""
        return self.addressed or not self.needs_name


@dataclass(kw_only=True)
class Heard:
    """An utterance, and what was made of it."""

    pcm: bytes
    text: str  # all of it, transcribed
    seconds: float  # how long it lasted
    speech_s: float = 0.0  # how much of it the VAD found voiced
    speech_end: float = 0.0  # time.monotonic() when speech ended
    transcribed: float = 0.0  # time.monotonic() when the text was ready
    speculated: bool = False  # transcribed during the final pause, before it was the end
    guess: str = ""  # what the last guess made of it, once one showed it's for grog
    needs_name: bool = True  # it had to start with grog's name
    addressed: bool = False  # a guess as it went on showed it's for grog
    chimed: bool = False  # its name, heard as it went on, already got its sound
    acked: float | None = None  # when the sound saying it got it started, if it did as it ended

    @property
    def said(self):
        """What was said, as heard: all of it, or when that came out empty
        while a guess showed it's for grog, that guess."""
        return self.text or (self.guess if self.addressed else "")

    @property
    def peak_db(self):
        return peak_dbfs(self.pcm)


class Ears:
    """Listens on `mic`, telling speech from silence with `vad`, and
    transcribes with `stt`. `partial_stt` transcribes partials (None: no
    partials): a speech to text that `stt` holds while it
    transcribes (hold() and release(), see worker.py), as what `stt`
    transcribes may be the end. `wake` gives the WakeWords to answer to
    (None: anything), `awake` why none is needed now (awake, asked) or None,
    `tell` tells grog what it's doing, `chime(name)` plays an earcon in the
    background, `report_heard(text, final)` tells grog what it heard for it,
    while `captions` says grog shows that (its skin.captions): only then is
    it reported, and are partials of what's for grog made to its end."""

    def __init__(
        self,
        settings,
        *,
        mic,
        vad,
        stt,
        partial_stt=None,
        wake=lambda: None,
        awake=lambda: None,
        tell=lambda state: None,
        chime=lambda name: None,
        report_heard=lambda text, final: None,
        captions=lambda: False,
        frame_bytes=FRAME_BYTES,
        frame_s=FRAME_S,
    ):
        self.mic, self.vad, self.stt, self.partial_stt = mic, vad, stt, partial_stt
        self.wake, self.awake, self.tell, self.chime = wake, awake, tell, chime
        self.report_heard, self.captions = report_heard, captions
        self.frame_bytes, self.frame_s = frame_bytes, frame_s
        self.partial_s = settings["partialMs"] / 1000
        self.held = False  # partials, since the last utterance ended
        self.forgetting = False  # what was being said is to be forgotten (see forget)
        self.endpointer = Endpointer(
            frame_bytes,
            frame_s,
            end_silence_s=settings["endSilenceMs"] / 1000,
            speculate_s=settings["speculateMs"] / 1000,
            max_s=settings["maxUtteranceSeconds"],
        )

    def command(self, text, needs_name, addressed=False):
        """What `text` asks grog: what comes after the wake word ("" when
        nothing does), all of it when it needs none (`needs_name` false, or
        no wake word) or when a guess already heard the name that `text`
        lost (`addressed`), or None when it isn't for grog."""
        if not text:
            return None
        wake = self.wake() if needs_name else None
        if wake is None:
            return text
        command = wake.match(text)
        return text if command is None and addressed else command

    def listen(self):
        """Waits for an utterance and transcribes it: at its last pause, in
        case that turns out to be the end, and while they talk, partials (see
        _partial), on partial_stt, which the other holds. Once a guess
        shows it's for grog, it hears it (see _guessed), and as it ends, says
        it got it (see _ended). Tells grog when someone talks to it, and when
        it's no longer awake: not when anyone else talks."""
        self.endpointer.reset()
        self.vad.reset()
        if self.held:
            # Partials can go on now.
            self.held = False
            self._release()
        partial = main = pause = utterance = None
        self.tell(self._idle())
        while True:
            t, frame = self.mic.read()
            if self.forgetting:
                self.forgetting = False
                self.endpointer.reset()
                self.vad.reset()
                partial = main = pause = utterance = None
            event = self.endpointer.push(frame, self.vad(frame))
            if isinstance(event, End):
                return self._ended(t, event, utterance, main, partial)
            if not self.endpointer.started:
                # Nothing yet, or a click, dropped with what was made of it.
                utterance = pause = main = partial = None
            elif utterance is None:
                utterance = Utterance(needs_name=self.wake() is not None and not self.awake())
            if isinstance(event, Pause):
                pause = event.audio
            elif isinstance(event, Resume):
                pause = None
            self._take(utterance, partial)
            self._take(utterance, main)
            if utterance is not None:
                if pause is not None and (main is None or main.done() and main.pcm != pause):
                    main = self._transcribe(pause, utterance, at_pause=True)
                if (partial is None or partial.done()) and (audio := self._partial(utterance, partial)):
                    partial = Transcription(self.partial_stt, audio, utterance)
            self.tell("hearing" if utterance is not None and utterance.for_grog() else self._idle())

    def address(self, heard):
        """What `heard` asks grog (see command), or None when it wasn't meant
        for grog. What's for grog is reported, all of it, after saying it
        hears it, if it didn't yet."""
        command = self.command(heard.said, heard.needs_name, heard.addressed)
        if command is not None:
            if not heard.addressed:
                self.tell("hearing")
            self._report(heard.said, True)
        return command

    def describe(self, heard):
        """How long `heard` was, how much of it speech and how loud: never
        what was said."""
        return f"{heard.seconds:.1f} s, {heard.speech_s:.1f} s of it speech, {sounded(heard.peak_db)}"

    def flush(self):
        """Drops what the mic heard so far."""
        self.mic.flush()

    def forget(self):
        """Forgets what was being said, if anything, with the next frame:
        after the mic was switched off, what's heard starts afresh."""
        self.forgetting = True

    def close(self):
        """Closes the mic, and what transcribes partials."""
        self.mic.close()
        if (close := getattr(self.partial_stt, "close", None)) is not None:
            close()

    def _idle(self):
        """What it does between utterances: listening, or awake or asked when
        what's said next needs no wake word (see `awake`)."""
        return self.awake() or "listening"

    def _report(self, text, final):
        """Tells grog what it heard for it, only while grog shows it."""
        if self.captions():
            self.report_heard(text, final)

    def _transcribe(self, pcm, utterance, at_pause=False):
        """`pcm` transcribed by stt, which may be the end: partials are held
        meanwhile, leaving it the CPU."""
        self._hold()
        return Transcription(self.stt, pcm, utterance, at_pause, then=self._release)

    def _hold(self):
        if self.partial_stt is not None:
            self.partial_stt.hold()

    def _release(self):
        if self.partial_stt is not None:
            self.partial_stt.release()

    def _take(self, utterance, guess):
        """Takes in what a guess made of `utterance`, once done: only once,
        not of another one, and not when it heard less than the last one (held
        while that was transcribed), but for silence."""
        if guess is not None and not guess.seen and guess.done():
            guess.seen = True
            if utterance is None or guess.utterance is not utterance:
                return
            if len(guess.pcm) + self.endpointer.tail_bytes >= utterance.upto:
                utterance.upto = max(utterance.upto, len(guess.pcm))
                self._guessed(utterance, guess)

    def _partial(self, utterance, last):
        """What was said so far, while someone talks, when it's worth
        transcribing: partialMs more of it since the last partial, from
        PARTIAL_FROM_S on. While it isn't known to be for grog, only in its
        first WAKE_WINDOW_S; once it is, if captions show it."""
        ep = self.endpointer
        if self.partial_stt is None or not ep.talking:
            return None
        seconds = ep.seconds
        mine = last is not None and last.utterance is utterance
        before = duration(last.pcm, self.frame_bytes, self.frame_s) if mine else 0
        if seconds < PARTIAL_FROM_S or seconds - before < self.partial_s:
            return None
        wanted = self.captions() if utterance.for_grog() else seconds <= WAKE_WINDOW_S
        return ep.audio() if wanted else None

    def _guessed(self, utterance, guess):
        """What a guess made of what was said so far. Once one shows it's for
        grog (its name first, or none needed), it hears it: tells grog, and
        its name gets a sound saying it listens, while they talk on (or when
        it came alone, at a pause). What it hears is reported as it comes.
        Its name counts at a pause, or with more after it: last, it may have
        been cut short and sound like another word ("Drog..." of "Droga")."""
        if not (text := guess.text()):
            return
        command = self.command(text, utterance.needs_name, utterance.addressed)
        if not utterance.addressed:
            if command is None or (utterance.needs_name and not command and not guess.at_pause):
                return
            utterance.addressed = True
            self.tell("hearing")
            if utterance.needs_name and (self.endpointer.talking or not command):
                self.chime("listening")
                utterance.chimed = True
        utterance.asks = bool(command)
        if text != utterance.guess:
            utterance.guess = text
            self._report(text, False)

    def _ended(self, t, event, utterance, main, partial):
        """The utterance ended: its text, the guess at its last pause when
        that was the end, or transcribed now. Partials are held until it
        listens again: no use now. Once a guess shows what it asks grog, the
        sound saying it got it plays meanwhile (see _got_it)."""
        if not self.held:
            self.held = True
            self._hold()
        self._take(utterance, partial)
        self._take(utterance, main)
        self._got_it(utterance)
        speculated = main is not None and main.pcm == event.audio
        if not speculated:
            if main is not None:
                main.wait()
                self._take(utterance, main)
                self._got_it(utterance)
            main = self._transcribe(event.audio, utterance)
        text = main.result()
        return Heard(
            pcm=event.audio,
            text=text,
            seconds=duration(event.audio, self.frame_bytes, self.frame_s),
            speech_s=event.speech_s,
            speech_end=t - event.silence_s,
            transcribed=time.monotonic(),
            speculated=speculated,
            guess=utterance.guess,
            needs_name=utterance.needs_name,
            addressed=utterance.addressed,
            chimed=utterance.chimed,
            acked=utterance.acked,
        )

    def _got_it(self, utterance):
        """Once a guess showed what the utterance that just ended asks grog
        (more than its name), the sound saying it got it, now, while its end
        is transcribed: grog is answering."""
        if utterance.addressed and utterance.asks and utterance.acked is None:
            utterance.acked = time.monotonic()
            self.chime("ack")
            self.tell("answering")
