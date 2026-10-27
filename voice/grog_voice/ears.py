"""grog-voice's ears: wait for an utterance and transcribe it, at its last
pause in case that's the end, and while someone talks, what they said so far
(partials): to hear grog's name before they're done, and to show their words
as they come. They decide whether it was for grog, never what to answer.

While grog plays music, the VAD takes it for speech: an utterance would run
on for as long as the song. So while it waits for its name over music, what
it hears is cut into short overlapping windows, each looked through for its
name (see _window).

They listen while grog answers too, for someone calling it then: what the
mic hears of grog's own sound, no louder than its echo, doesn't start an
utterance (see echo.py)."""

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
# While it isn't known to be for grog, only this much of its start is
# transcribed, at pauses and at its end: grog's name comes in its first
# words (see wake.MAX_LEADING_WORDS), and what others say, which often runs
# on for maxUtteranceSeconds (a TV, people talking), isn't worth the CPU.
HEAD_S = 3.0
# Over music, how much each window overlaps the one before: a name said
# across a cut is whole in one of them.
MUSIC_OVERLAP_S = 1.0
# While grog answers, how long it waits for a frame at most before seeing
# whether it's done.
UNTIL_S = 0.1


class Transcription:
    """Speech to text of `pcm`, on a thread, calling `then` once done: a guess
    at what `utterance` says so far, at a pause (`at_pause`) or while they
    talk (a partial), or all of it, or over music, a window of it starting
    `start` bytes into it (`window`). `seen` once what it made of it was
    taken in."""

    def __init__(self, stt, pcm, utterance, at_pause=False, then=lambda: None, window=False, start=0):
        self.pcm, self.utterance, self.at_pause = pcm, utterance, at_pause
        self.window, self.start = window, start
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
    follows: bool = False  # it could follow grog's answer without the name (see Ears)
    started: float = 0.0  # time.monotonic() when its speech started, as the mic stamps it
    addressed: bool = False  # a guess showed it's for grog
    asks: bool = False  # the last guess asks grog something, more than its name
    guess: str = ""  # the text of the last guess, once one showed it's for grog
    upto: int = 0  # how much of it that guess heard (bytes)
    chimed: bool = False  # its name got its sound as it went on
    acked: float | None = None  # when the sound saying it got it started, if it did as it ended

    cut: bool = False  # cut into windows over music: it starts anywhere now, and so may its name
    next_cut: int | None = None  # where the next window over music ends (bytes into it)
    dropped: int = 0  # how much of its start was let go of (bytes)
    start: int = 0  # where what's for grog starts, once a window heard its name (bytes into it)
    head: Transcription | None = None  # of its start (HEAD_S), once longer, while not known to be for grog

    def for_grog(self):
        """Whether someone is known to talk to grog."""
        return self.addressed or not self.needs_name

    def maybe_for_grog(self):
        """Whether someone talks to grog, or could: it may follow its answer."""
        return self.for_grog() or self.follows


@dataclass(kw_only=True)
class Heard:
    """An utterance, and what was made of it."""

    pcm: bytes
    text: str  # all of it, transcribed; only its start (HEAD_S) when that showed it isn't for grog
    seconds: float  # how long it lasted
    speech_s: float = 0.0  # how much of it the VAD found voiced
    speech_end: float = 0.0  # time.monotonic() when speech ended
    transcribed: float = 0.0  # time.monotonic() when the text was ready
    speculated: bool = False  # transcribed during the final pause, before it was the end
    guess: str = ""  # what the last guess made of it, once one showed it's for grog
    needs_name: bool = True  # it had to start with grog's name
    follows: bool = False  # it could follow grog's answer without the name (see Ears)
    angle: float | None = None  # where it came from, if known (see Ears)
    addressed: bool = False  # a guess as it went on showed it's for grog
    chimed: bool = False  # its name, heard as it went on, already got its sound
    acked: float | None = None  # when the sound saying it got it started, if it did as it ended
    anywhere: bool = False  # it was cut out of what was heard over music: its name may be anywhere

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
    it reported, and are partials of what's for grog made to its end.
    `music` says whether grog plays music: while it does, it looks for its
    name in windows of musicUtteranceSeconds (see _window). `echo` (an
    Echo, or None) keeps grog's own sound from being taken for speech;
    `busy` says what grog-voice does while grog answers (answering,
    speaking), or None; `called` is told when someone turns out to talk to
    grog, while they go on. `follows` says whether what's said now, without
    its name, could follow grog's answer, if it comes from where whoever it
    answered talked from (see loop.Voice.follow): transcribed whole then,
    the voice deciding once it's over; `where(start, end)`, where speech
    heard between two times came from (an angle, see ring.Ring.angle), or
    None."""

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
        music=lambda: False,
        echo=None,
        busy=lambda: None,
        called=lambda: None,
        follows=lambda: False,
        where=lambda start, end: None,
        frame_bytes=FRAME_BYTES,
        frame_s=FRAME_S,
    ):
        self.mic, self.vad, self.stt, self.partial_stt = mic, vad, stt, partial_stt
        self.wake, self.awake, self.tell, self.chime = wake, awake, tell, chime
        self.report_heard, self.captions, self.music = report_heard, captions, music
        self.echo, self.busy, self.called = echo, busy, called
        self.follows, self.where = follows, where
        self.frame_bytes, self.frame_s = frame_bytes, frame_s
        self.partial_s = settings["partialMs"] / 1000
        self.window_bytes = round(settings["musicUtteranceSeconds"] / frame_s) * frame_bytes
        self.step_bytes = self.window_bytes - round(MUSIC_OVERLAP_S / frame_s) * frame_bytes
        self.head_bytes = round(HEAD_S / frame_s) * frame_bytes
        self.held = False  # partials, since the last utterance ended
        self.forgetting = False  # what was being said is to be forgotten (see forget)
        self.heard_at = time.monotonic()  # when it last took a frame from the mic, or found none
        self.endpointer = Endpointer(
            frame_bytes,
            frame_s,
            end_silence_s=settings["endSilenceMs"] / 1000,
            speculate_s=settings["speculateMs"] / 1000,
            max_s=settings["maxUtteranceSeconds"],
        )

    def command(self, text, needs_name, addressed=False, anywhere=False):
        """What `text` asks grog: what comes after the wake word ("" when
        nothing does), all of it when it needs none (`needs_name` false, or
        no wake word) or when a guess already heard the name that `text`
        lost (`addressed`), or None when it isn't for grog. `anywhere`: the
        wake word may be anywhere in it, not only first, as it was cut out of
        what was heard over music."""
        if not text:
            return None
        wake = self.wake() if needs_name else None
        if wake is None:
            return text
        command = wake.match(text, anywhere)
        return text if command is None and addressed else command

    def listen(self, until=None):
        """Waits for an utterance and transcribes it: at its last pause, in
        case that turns out to be the end, and while they talk, partials (see
        _partial), on partial_stt, which the other holds. Once a guess
        shows it's for grog, it hears it (see _guessed), and as it ends, says
        it got it (see _ended). Over music, while it waits for its name, it
        looks for it in windows (see _window), on stt, one transcription at a
        time there. Longer than HEAD_S and not known to be for grog, only its
        start is transcribed, unless it could follow grog's answer (see
        `follows`). While grog's own sound plays, what's no louder than its
        echo doesn't start one (see echo.py). Tells grog when someone talks
        to it, and when it's no longer awake: not when anyone else talks.
        While grog answers, `until` says when that's over: then, with nobody
        talking, it returns None."""
        self.endpointer.reset()
        self.vad.reset()
        if self.held:
            # Partials can go on now.
            self.held = False
            self._release()
        partial = main = pause = utterance = window = None
        self.tell(self._idle())
        while True:
            if until is not None and utterance is None and until():
                return None
            # While grog answers, it can be over with no frame coming (the
            # mic switched off, say).
            read = self.mic.read(timeout=None if until is None else UNTIL_S)
            self.heard_at = time.monotonic()
            if read is None:
                continue
            t, frame = read
            if self.forgetting:
                self.forgetting = False
                self.endpointer.reset()
                self.vad.reset()
                partial = main = pause = utterance = window = None
            prob = self.vad(frame)
            if self.echo is not None and not self.endpointer.started:
                prob = self.echo.gate(t, frame, prob)
            event = self.endpointer.push(frame, prob)
            if isinstance(event, End):
                return self._ended(t, event, utterance, main, partial, window)
            if not self.endpointer.started:
                # Nothing yet, or a click, dropped with what was made of it.
                utterance = pause = main = partial = window = None
            elif utterance is None:
                needs_name = self.wake() is not None and not self.awake()
                utterance = Utterance(needs_name=needs_name, follows=needs_name and self.follows(), started=t)
            if isinstance(event, Pause):
                pause = event.audio
            elif isinstance(event, Resume):
                pause = None
            dropped = utterance.dropped if utterance is not None else 0
            self._take(utterance, partial)
            self._take(utterance, main)
            self._take(utterance, window)
            if utterance is not None:
                if utterance.dropped != dropped:
                    pause = None  # of what it let go of
                free = window is None or window.done()
                ready = free and (main is None or main.done())
                fresh = pause is not None and (main is None or main.pcm != pause)
                if ready and fresh and self._whole(utterance, len(pause)):
                    main = self._transcribe(pause, utterance, at_pause=True)
                elif ready and utterance.head is None and not self._whole(utterance, self.endpointer.size):
                    # Only its start tells whether it's for grog (see HEAD_S).
                    audio = self.endpointer.audio()[: self.head_bytes]
                    main = utterance.head = self._transcribe(audio, utterance)
                if (partial is None or partial.done()) and (audio := self._partial(utterance, partial)):
                    partial = Transcription(self.partial_stt, audio, utterance)
                if free and (main is None or main.done()) and (cut := self._window(utterance)):
                    start, audio = cut
                    window = self._transcribe(audio, utterance, window=True, start=start)
            if utterance is not None and utterance.for_grog():
                self.tell("hearing")
            elif until is None or not until():
                # Once grog's answer is over, what's next is said next: awake
                # or asked, maybe.
                self.tell(self._idle())

    def address(self, heard, follows=False):
        """What `heard` asks grog (see command), or None when it wasn't meant
        for grog: all of it, without grog's name, when it `follows` grog's
        answer (see loop.Voice.follow). What's for grog is reported, all of
        it, after saying it hears it, if it didn't yet."""
        needs_name = heard.needs_name and not follows
        command = self.command(heard.said, needs_name, heard.addressed, heard.anywhere)
        if command is not None:
            if not heard.addressed:
                self.tell("hearing")
            self._report(heard.said, True)
        return command

    def describe(self, heard):
        """How long `heard` was, how much of it speech and how loud: never
        what was said."""
        return f"{heard.seconds:.1f} s, {heard.speech_s:.1f} s of it speech, {sounded(heard.peak_db)}"

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
        """What it does between utterances: answering or speaking while grog
        answers (see `busy`), listening, or awake or asked when what's said
        next needs no wake word (see `awake`)."""
        return self.busy() or self.awake() or "listening"

    def _report(self, text, final):
        """Tells grog what it heard for it, only while grog shows it."""
        if self.captions():
            self.report_heard(text, final)

    def _transcribe(self, pcm, utterance, at_pause=False, window=False, start=0):
        """`pcm` transcribed by stt, which may be the end: partials are held
        meanwhile, leaving it the CPU."""
        self._hold()
        return Transcription(
            self.stt, pcm, utterance, at_pause, then=self._release, window=window, start=start
        )

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
            if guess.window:
                self._windowed(utterance, guess)
            elif len(guess.pcm) + self.endpointer.tail_bytes >= utterance.upto:
                utterance.upto = max(utterance.upto, len(guess.pcm))
                self._guessed(utterance, guess)

    def _partial(self, utterance, last):
        """What was said so far, while someone talks, when it's worth
        transcribing: partialMs more of it since the last partial, from
        PARTIAL_FROM_S on. While it isn't known to be for grog, only in its
        first WAKE_WINDOW_S, and not once it was cut into windows over music
        (they look for its name then); once it is, if captions show it."""
        ep = self.endpointer
        if self.partial_stt is None or not ep.talking:
            return None
        seconds = ep.seconds
        mine = last is not None and last.utterance is utterance
        before = duration(last.pcm, self.frame_bytes, self.frame_s) if mine else 0
        if seconds < PARTIAL_FROM_S or seconds - before < self.partial_s:
            return None
        wanted = self.captions() if utterance.for_grog() else seconds <= WAKE_WINDOW_S and not utterance.cut
        return ep.audio() if wanted else None

    def _over_music(self, utterance):
        """Whether it waits for its name over music."""
        return utterance.needs_name and not utterance.addressed and self.music()

    def _whole(self, utterance, size):
        """Whether all of what was said so far (`size` bytes) is worth
        transcribing: it's for grog (or needs no name, or could follow its
        answer), it waits for its name over music (see _window), or it's no
        longer than the start that tells (HEAD_S)."""
        return (
            utterance.maybe_for_grog()
            or utterance.cut
            or self._over_music(utterance)
            or size <= self.head_bytes
        )

    def _named(self, utterance, audio, main):
        """Whether `audio`, what was said, turns out to be for grog by its
        start (HEAD_S), transcribed now unless it was, once `main` is done:
        one transcription at a time on stt."""
        if utterance.head is None:
            if main is not None:
                main.wait()
                self._take(utterance, main)
            if utterance.for_grog():
                return True
            utterance.head = self._transcribe(audio[: self.head_bytes], utterance)
        utterance.head.wait()
        self._take(utterance, utterance.head)
        self._got_it(utterance)
        return utterance.for_grog()

    def _window(self, utterance):
        """Over music, while it waits for its name, the next window of what
        was heard to look for it in, once it's all there: (where it starts,
        its audio), or None. Windows are musicUtteranceSeconds long, each
        starting MUSIC_OVERLAP_S before the one before ends; the first ends
        musicUtteranceSeconds in, or as music starts when it's longer by
        then. When it falls behind, it skips to the latest one."""
        if not self._over_music(utterance):
            return None
        end = utterance.dropped + self.endpointer.size
        if utterance.next_cut is None:
            utterance.next_cut = max(self.window_bytes, end)
        if end < utterance.next_cut:
            return None
        cut = utterance.next_cut + (end - utterance.next_cut) // self.step_bytes * self.step_bytes
        utterance.next_cut, utterance.cut = cut + self.step_bytes, True
        start = max(utterance.dropped, cut - self.window_bytes)
        return start, self.endpointer.audio()[start - utterance.dropped : cut - utterance.dropped]

    def _windowed(self, utterance, window):
        """What a window over music made of what was heard. With its name in
        it, anywhere, what's for grog starts with that window: what came
        before goes, and it hears it as from a guess (see _guessed). Without
        it, what comes before the next window goes."""
        if utterance.addressed:
            return
        text = window.text()
        if text and self.command(text, needs_name=True, anywhere=True) is not None:
            utterance.start = window.start
            self._drop(utterance, window.start)
            utterance.upto = len(window.pcm)
            self._guessed(utterance, window)
        elif utterance.next_cut is not None:
            self._drop(utterance, utterance.next_cut - self.window_bytes)

    def _from_name(self, text):
        """`text`, cut out of what was heard over music, from grog's name on,
        when it's in it: what came before (the song's words, or someone
        else's) is neither shown nor asked."""
        wake = self.wake()
        found = wake.find(text, anywhere=True) if wake is not None and text else None
        return text if found is None else text[found[0] :]

    def _drop(self, utterance, to):
        """Lets go of what the utterance under way heard before `to` (bytes
        into it)."""
        n = min(to - utterance.dropped, self.endpointer.size)
        if n > 0 and self.endpointer.started:
            n -= n % self.frame_bytes
            self.endpointer.drop(n)
            utterance.dropped += n
            utterance.upto = max(0, utterance.upto - n)

    def _guessed(self, utterance, guess):
        """What a guess made of what was said so far. Once one shows it's for
        grog (its name first, or none needed), it hears it: says so
        (`called`: what grog answers is cut off), tells grog, and its name
        gets a sound saying it listens, while they talk on (or when it came
        alone, at a pause). What it hears is reported as it comes.
        Its name counts at a pause, or with more after it: last, it may have
        been cut short and sound like another word ("Drog..." of "Droga"),
        but in a window over music, where people say its name and wait (the
        next window starts before it ends: a name cut short there is whole
        in it)."""
        if not (text := guess.text()):
            return
        if utterance.cut:
            text = self._from_name(text)
        command = self.command(text, utterance.needs_name, utterance.addressed, utterance.cut)
        if not utterance.addressed:
            last = utterance.needs_name and not command and not (guess.at_pause or guess.window)
            if command is None or last:
                return
            utterance.addressed = True
            self.called()
            self.tell("hearing")
            if utterance.needs_name and (self.endpointer.talking or not command):
                self.chime("listening")
                utterance.chimed = True
        utterance.asks = bool(command)
        if text != utterance.guess:
            utterance.guess = text
            self._report(text, False)

    def _ended(self, t, event, utterance, main, partial, window):
        """The utterance ended: its text, the guess at its last pause when
        that was the end, or transcribed now: from the window over music
        that heard its name, if one did; only its start's (HEAD_S) when that
        shows it isn't for grog. Partials are held until it listens again: no
        use now. Once a guess shows what it asks grog, the sound saying it
        got it plays meanwhile (see _got_it)."""
        if not self.held:
            self.held = True
            self._hold()
        self._take(utterance, partial)
        self._take(utterance, main)
        if window is not None:
            window.wait()
            self._take(utterance, window)
        self._got_it(utterance)
        audio = event.audio[max(0, utterance.start - utterance.dropped) :]
        if not self._whole(utterance, len(audio)) and not self._named(utterance, audio, main):
            # Not for grog, as its start tells: the rest isn't transcribed.
            return self._heard(t, event, utterance, audio, utterance.head.result(), speculated=False)
        speculated = main is not None and main.pcm == audio
        if not speculated:
            if main is not None:
                main.wait()
                self._take(utterance, main)
                self._got_it(utterance)
            main = self._transcribe(audio, utterance)
        text = main.result()
        if utterance.cut:
            text = self._from_name(text)
        return self._heard(t, event, utterance, audio, text, speculated)

    def _heard(self, t, event, utterance, audio, text, speculated):
        """What was heard of `utterance`, which ended (`event`) at `t`: its
        `audio`, as `text`, and where it came from, if known (see `where`)."""
        seconds = duration(audio, self.frame_bytes, self.frame_s)
        speech_end = t - event.silence_s
        return Heard(
            pcm=audio,
            text=text,
            seconds=seconds,
            speech_s=min(event.speech_s, seconds),
            speech_end=speech_end,
            transcribed=time.monotonic(),
            speculated=speculated,
            guess=utterance.guess,
            needs_name=utterance.needs_name,
            follows=utterance.follows,
            angle=self.where(utterance.started, speech_end),
            addressed=utterance.addressed,
            chimed=utterance.chimed,
            acked=utterance.acked,
            anywhere=utterance.cut,
        )

    def _got_it(self, utterance):
        """Once a guess showed what the utterance that just ended asks grog
        (more than its name), the sound saying it got it, now, while its end
        is transcribed: grog is answering."""
        if utterance.addressed and utterance.asks and utterance.acked is None:
            utterance.acked = time.monotonic()
            self.chime("ack")
            self.tell("answering")
