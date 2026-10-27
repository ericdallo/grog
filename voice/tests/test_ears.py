import itertools
import threading
import unittest

from fakes import (
    ECHO,
    FRAME,
    FRAME_S,
    HEARD,
    PARTIAL,
    SETTINGS,
    SILENCE,
    SPEECH,
    FakeMic,
    FakeStt,
    FakeVad,
    Held,
    OverMusic,
    Saying,
    SimMic,
    frames,
    numbered,
    seconds,
    slow_mic,
    song,
)

from grog_voice import ears as ears_module
from grog_voice.audio import MicError
from grog_voice.ears import Ears, Heard
from grog_voice.echo import Echo
from grog_voice.wake import WakeWords

GROG = WakeWords(["Grog"])


def echoing(seconds=30.0):
    """An Echo of grog speaking for `seconds` from now."""
    echo = Echo(FRAME_S)
    echo.played(seconds, speech=True)
    return echo


class Told(list):
    """What it told grog it's doing, when that changed (as Voice does)."""

    def __call__(self, state):
        if not self or self[-1] != state:
            self.append(state)


def ears(
    mic_frames=(),
    *,
    mic=None,
    stt=None,
    wake=None,
    settings=None,
    partials=None,
    captions=False,
    awake=None,
    music=lambda: False,
    echo=None,
    busy=lambda: None,
    follows=lambda: False,
    where=lambda start, end: None,
):
    """Ears with fakes, with partials by `partials` (a speech to text) when
    given, awake for a reason when `awake` says one (awake, asked), music
    playing while `music()`, grog's own sound told apart by `echo`, and
    grog answering while `busy()` says how; what's said could follow its
    answer while `follows()`, and came from where `where` says; what they tell grog is in .told,
    the earcons they chime in .chimes, what they heard for grog in
    .heard_reports ((text, final)), how many times someone turned out to
    talk to grog as they went on in .called."""
    told, chimes, heard = Told(), [], []
    e = Ears(
        settings or (PARTIAL if partials is not None else SETTINGS),
        mic=mic or FakeMic(mic_frames),
        vad=FakeVad(),
        stt=stt or FakeStt(HEARD),
        partial_stt=Held(partials) if partials is not None else None,
        wake=lambda: wake,
        awake=lambda: awake,
        tell=told,
        chime=chimes.append,
        report_heard=lambda text, final: heard.append((text, final)),
        captions=lambda: captions,
        music=music,
        echo=echo,
        busy=busy,
        called=lambda: setattr(e, "called_count", e.called_count + 1),
        follows=follows,
        where=where,
        frame_bytes=FRAME,
        frame_s=FRAME_S,
    )
    e.told, e.chimes, e.heard_reports, e.called_count = told, chimes, heard, 0
    return e


class ListenTest(unittest.TestCase):
    def test_an_utterance(self):
        e = ears(mic=slow_mic(("S", 1.0), (".", 1.0)))
        heard = e.listen()
        self.assertEqual(HEARD, heard.text)
        self.assertTrue(heard.speculated, "transcribed during the pause")
        self.assertEqual([heard.pcm], e.stt.calls)
        # No preroll (nothing before it): the whole second, plus 0.2 s of tail.
        self.assertEqual(SPEECH * 31 + SILENCE * 6, heard.pcm)
        self.assertAlmostEqual(37 * FRAME_S, heard.seconds)
        # What it made of it at the pause asked something: got it, as it ended.
        self.assertLessEqual(heard.speech_end, heard.acked)
        self.assertEqual(["ack"], e.chimes)
        self.assertEqual(["listening", "hearing", "answering"], e.told)

    def test_describes_how_it_sounded(self):
        e = ears(frames(("S", 1.0), (".", 1.0)))
        # 37 frames of 32 ms, 31 of them voiced; peaking at 0x5353 ("SS").
        self.assertEqual("1.2 s, 1.0 s of it speech, peak -4 dBFS", e.describe(e.listen()))
        quiet = Heard(pcm=b"\x10\x00" * 100, text="", seconds=1.6, speech_s=0.4)  # peaking at 16
        self.assertEqual(
            "1.6 s, 0.4 s of it speech, peak -66 dBFS, transcribed 30 dB louder", e.describe(quiet)
        )

    def test_speech_going_on_after_a_pause_drops_the_guess(self):
        e = ears(frames(("S", 0.5), (".", 0.4), ("S", 0.5), (".", 1.0)), stt=FakeStt("que horas", HEARD))
        heard = e.listen()
        self.assertEqual(HEARD, heard.text)
        self.assertEqual(2, len(e.stt.calls))
        self.assertLess(len(e.stt.calls[0]), len(e.stt.calls[1]))
        self.assertEqual(heard.pcm, e.stt.calls[1])

    def test_without_speculation(self):
        e = ears(frames(("S", 1.0), (".", 1.0)), settings={**SETTINGS, "speculateMs": 0})
        heard = e.listen()
        self.assertFalse(heard.speculated)
        self.assertEqual([heard.pcm], e.stt.calls)
        # Nothing guessed before the end: got it once it's answered.
        self.assertIsNone(heard.acked)
        self.assertEqual([], e.chimes)

    def test_a_click_is_heard_then_dropped(self):
        # Speech too short to be anything, then silence: back to listening.
        e = ears(frames(("S", 0.1), (".", 1.5)))
        with self.assertRaises(MicError):
            e.listen()
        self.assertEqual(["listening", "hearing", "listening"], e.told)

    def test_the_end_never_waits_for_what_was_made_of_a_click(self):
        # The click paused: what it said is transcribed, slowly. The next
        # utterance ends meanwhile.
        click_done, go_on = threading.Event(), threading.Event()

        def stt(pcm):
            if seconds(pcm) < 0.6:
                go_on.wait(5)
                click_done.set()
                return "hm"
            return HEARD

        e = ears(frames(("S", 0.1), (".", 1.5), ("S", 1.0), (".", 1.0)), stt=stt)
        self.assertEqual(HEARD, e.listen().text)
        self.assertFalse(click_done.is_set(), "it didn't wait for it")
        go_on.set()

    def test_a_failed_transcription_is_raised(self):
        e = ears(frames(("S", 1.0), (".", 1.0)), stt=FakeStt(RuntimeError("boom")))
        with self.assertRaises(RuntimeError):
            e.listen()

    def test_forgets_what_was_being_said(self):
        # Someone talks, and the mic is switched off and on again meanwhile:
        # what they said before is forgotten.
        class Switched(FakeMic):
            def read(self, timeout=None):
                if self.read_count == 20:
                    e.forget()
                return super().read(timeout)

        e = ears(mic=Switched(frames(("S", 2.0), (".", 1.0))), settings={**SETTINGS, "speculateMs": 0})
        heard = e.listen()
        self.assertEqual(SPEECH * 42 + SILENCE * 6, heard.pcm, "only what came after")
        self.assertFalse(e.forgetting)


class WhileGrogAnswersTest(unittest.TestCase):
    """It listens while grog answers: grog's own sound isn't someone talking,
    and someone calling grog is said as they go on."""

    def test_its_echo_starts_nothing(self):
        e = ears([ECHO] * 200, echo=echoing())
        with self.assertRaises(MicError):
            e.listen()
        self.assertEqual([], e.stt.calls, "nothing transcribed")
        self.assertEqual(["listening"], e.told)

    def test_someone_heard_over_it_is(self):
        e = ears([ECHO] * 40 + frames(("S", 1.0), (".", 1.0)), echo=echoing())
        heard = e.listen()
        self.assertEqual(HEARD, heard.text)
        self.assertEqual([heard.pcm], e.stt.calls)
        self.assertTrue(heard.pcm.endswith(SPEECH * 31 + SILENCE * 6), "all they said")
        self.assertEqual(10, heard.pcm.count(ECHO), "with what came just before")

    def test_its_sound_while_someone_talks_cuts_nothing_off(self):
        # The sound saying it heard its name plays while they talk on.
        said = "Grog, que horas são agora em Lisboa?"
        echo = Echo(FRAME_S)
        e = ears(mic=slow_mic(("S", 2.0), (".", 1.0)), stt=Saying(said), partials=Saying(said), wake=GROG)
        e.echo = echo
        e.chime = lambda name: (e.chimes.append(name), echo.played(1.0))
        heard = e.listen()
        self.assertEqual(["listening", "ack"], e.chimes)
        self.assertEqual(said, heard.text)
        self.assertAlmostEqual(2.0, heard.speech_s, delta=0.05)

    def test_its_name_is_said_as_they_go_on(self):
        said = "Grog, que horas são agora em Lisboa?"
        e = ears(mic=slow_mic(("S", 2.0), (".", 1.0)), stt=Saying(said), partials=Saying(said), wake=GROG)
        e.listen()
        self.assertEqual(1, e.called_count)
        e = ears(mic=slow_mic(("S", 2.0), (".", 1.0)), stt=Saying("Droga, esqueci a chave."), wake=GROG)
        self.assertIsNone(e.address(e.listen()))
        self.assertEqual(0, e.called_count, "not for what isn't for grog")

    def test_returns_once_grog_is_done_with_nobody_talking(self):
        done = threading.Event()

        class Done(FakeMic):
            def read(self, timeout=None):
                if self.read_count == 10:
                    done.set()
                return super().read(timeout)

        e = ears(mic=Done(frames((".", 2.0))), busy=lambda: None if done.is_set() else "speaking")
        self.assertIsNone(e.listen(until=done.is_set))
        self.assertEqual(["speaking"], e.told, "as grog's answer has it, until then: what's next comes next")
        self.assertLess(e.mic.read_count, 15)

    def test_what_was_being_said_as_grog_was_done_is_heard_to_its_end(self):
        done = threading.Event()

        class Done(FakeMic):
            def read(self, timeout=None):
                if self.read_count == 20:
                    done.set()
                return super().read(timeout)

        e = ears(mic=Done(frames(("S", 1.0), (".", 1.0))))
        heard = e.listen(until=done.is_set)
        self.assertEqual(HEARD, heard.text)
        self.assertTrue(heard.pcm.endswith(SPEECH * 31 + SILENCE * 6))


class AddressTest(unittest.TestCase):
    def test_what_a_text_asks_grog(self):
        e = ears(wake=GROG)
        self.assertEqual("que horas são?", e.command("Grog, que horas são?", needs_name=True))
        self.assertEqual("", e.command("Grog.", needs_name=True), "just its name")
        self.assertIsNone(e.command("Droga, esqueci.", needs_name=True))
        self.assertIsNone(e.command("", needs_name=True))
        self.assertEqual("Droga, esqueci.", e.command("Droga, esqueci.", needs_name=False), "none needed")
        self.assertEqual("Droga, que horas?", e.command("Droga, que horas?", True, addressed=True))
        self.assertEqual("Oi.", ears().command("Oi.", needs_name=True), "no wake word: anything")

    def test_its_name_at_a_pause_gets_a_sound_right_away(self):
        # "Grog... que horas são?": what's guessed at the pause starts with its name.
        mic = slow_mic(("S", 0.5), (".", 0.4), ("S", 1.0), (".", 1.0))
        e = ears(mic=mic, stt=FakeStt("Grog.", "Grog, que horas são?"), wake=GROG, captions=True)
        heard = e.listen()
        self.assertTrue(heard.chimed)
        self.assertEqual(["listening", "ack"], e.chimes, "while they talk on, then got it as they're done")
        self.assertEqual("que horas são?", e.address(heard))
        self.assertEqual(
            [("Grog.", False), ("Grog, que horas são?", False), ("Grog, que horas são?", True)],
            e.heard_reports,
            "as it comes",
        )

    def test_just_its_name(self):
        e = ears(mic=slow_mic(("S", 0.5), (".", 1.5)), wake=GROG, stt=FakeStt("Grog."), captions=True)
        heard = e.listen()
        self.assertEqual("", e.address(heard))
        self.assertEqual((["listening"], True), (e.chimes, heard.chimed))
        self.assertEqual([("Grog.", False), ("Grog.", True)], e.heard_reports)

    def test_what_isnt_for_it_is_neither_shown_nor_sounded(self):
        mic = slow_mic(("S", 0.5), (".", 0.4), ("S", 1.0), (".", 1.0))
        e = ears(mic=mic, stt=FakeStt("Droga.", "Droga, esqueci a chave."), wake=GROG, captions=True)
        heard = e.listen()
        self.assertIsNone(e.address(heard))
        self.assertEqual(([], [], ["listening"]), (e.chimes, e.heard_reports, e.told))

    def test_awake_it_needs_no_name(self):
        e = ears(frames(("S", 1.0), (".", 1.0)), wake=GROG, awake="awake", captions=True)
        heard = e.listen()
        self.assertEqual(HEARD, e.address(heard))
        self.assertEqual(["awake", "hearing", "answering"], e.told)
        self.assertEqual([(HEARD, False), (HEARD, True)], e.heard_reports)

    def test_after_its_question_it_says_so(self):
        e = ears(frames(("S", 1.0), (".", 1.0)), wake=GROG, awake="asked")
        heard = e.listen()
        self.assertEqual(HEARD, e.address(heard), "the reply needs no name either")
        self.assertEqual(["asked", "hearing", "answering"], e.told)

    def test_says_it_heard_when_only_the_whole_shows_its_name(self):
        e = ears(
            frames(("S", 1.0), (".", 1.0)),
            stt=FakeStt("Grog, que horas são?"),
            wake=GROG,
            settings={**SETTINGS, "speculateMs": 0},
            captions=True,
        )
        heard = e.listen()
        self.assertEqual(["listening"], e.told)
        self.assertEqual("que horas são?", e.address(heard))
        self.assertEqual(["listening", "hearing"], e.told)
        self.assertEqual([("Grog, que horas são?", True)], e.heard_reports)

    def test_what_it_heard_stays_in_without_captions(self):
        mic = slow_mic(("S", 0.5), (".", 0.4), ("S", 1.0), (".", 1.0))
        e = ears(mic=mic, stt=FakeStt("Grog.", "Grog, que horas são?"), wake=GROG)
        self.assertEqual("que horas são?", e.address(e.listen()))
        self.assertEqual([], e.heard_reports)


class HeadTest(unittest.TestCase):
    """What isn't known to be for grog is only transcribed as far as its
    name could be: its start (HEAD_S)."""

    HEAD = round(ears_module.HEAD_S / FRAME_S) * FRAME
    TALK = (("S", 2.0), (".", 0.4), ("S", 2.0), (".", 0.4), ("S", 2.0), (".", 1.0))

    def test_someone_else_talking_on_is_transcribed_only_at_its_start(self):
        e = ears(mic=slow_mic(*self.TALK), wake=GROG, stt=FakeStt("o jogo de ontem foi bom demais"))
        heard = e.listen()
        self.assertGreater(len(heard.pcm), 2 * self.HEAD)
        self.assertTrue(e.stt.calls and all(len(pcm) <= self.HEAD for pcm in e.stt.calls), "never all of it")
        self.assertEqual(self.HEAD, len(e.stt.calls[-1]), "its start")
        self.assertEqual("o jogo de ontem foi bom demais", heard.text)
        self.assertIsNone(e.address(heard))
        self.assertEqual(["listening"], e.told)

    def test_without_pauses_its_start_once(self):
        e = ears(frames(("S", 4.0), (".", 1.0)), wake=GROG, stt=FakeStt("o jogo de ontem"))
        heard = e.listen()
        self.assertEqual([self.HEAD], [len(pcm) for pcm in e.stt.calls])
        self.assertEqual("o jogo de ontem", heard.text)

    def test_its_name_at_its_start_has_all_of_it_transcribed(self):
        text = "Grog, me conta uma história bem longa sobre dragões"
        e = ears(mic=slow_mic(*self.TALK), wake=GROG, stt=FakeStt(text))
        heard = e.listen()
        self.assertEqual(heard.pcm, e.stt.calls[-1], "all of it")
        self.assertEqual(text, heard.text)
        self.assertEqual("me conta uma história bem longa sobre dragões", e.address(heard))

    def test_its_name_in_its_start_without_pauses_then_all_of_it(self):
        text = "Grog, me conta uma história"
        e = ears(frames(("S", 4.0), (".", 1.0)), wake=GROG, stt=FakeStt(text))
        heard = e.listen()
        self.assertEqual([self.HEAD, len(heard.pcm)], [len(pcm) for pcm in e.stt.calls])
        self.assertEqual(text, heard.text)
        self.assertIn("answering", e.told)

    def test_awake_all_of_it(self):
        e = ears(mic=slow_mic(*self.TALK), wake=GROG, awake="asked", stt=FakeStt("sim, e depois mais coisa"))
        heard = e.listen()
        self.assertEqual(heard.pcm, e.stt.calls[-1])

    def test_what_could_follow_grogs_answer_all_of_it_and_where_it_came_from(self):
        spans = []
        e = ears(
            mic=SimMic(frames(*self.TALK)),
            wake=GROG,
            stt=FakeStt("e amanhã, vai chover lá em Pinheiros?"),
            follows=lambda: True,
            where=lambda start, end: spans.append((start, end)) or 120.0,
        )
        heard = e.listen()
        self.assertEqual(heard.pcm, e.stt.calls[-1], "all of it")
        self.assertTrue(heard.follows and heard.needs_name)
        self.assertEqual(120.0, heard.angle)
        [(start, end)] = spans
        self.assertEqual(heard.speech_end, end, "from when its speech started to when it ended")
        self.assertAlmostEqual(heard.speech_end - start, 6.8, delta=0.5)
        self.assertIsNone(e.address(heard), "for the voice to decide")
        self.assertEqual(["following"], e.told, "what it does meanwhile, for grog's skin")
        self.assertEqual("e amanhã, vai chover lá em Pinheiros?", e.address(heard, follows=True))
        self.assertEqual(["following", "hearing"], e.told)

    def test_with_a_name_needed_only(self):
        for awake in (None, "awake"):
            e = ears(frames(("S", 1.0), (".", 1.0)), wake=GROG, awake=awake, follows=lambda: True)
            self.assertEqual(awake is None, e.listen().follows, awake)
        e = ears(frames(("S", 1.0), (".", 1.0)), wake=GROG)
        heard = e.listen()
        self.assertFalse(heard.follows)
        self.assertIsNone(heard.angle)


def over_music(*parts):
    """A FakeMic of `parts`, frames, 2 ms each: windows are done before the
    next one is due."""
    return FakeMic([frame for part in parts for frame in part], frame_wait_s=0.002)


# Windows' frames: musicUtteranceSeconds (4 s) in 32 ms frames, each window
# starting 31 frames (MUSIC_OVERLAP_S) before the one before ends.
WINDOW, STEP = 125, 125 - 31


class MusicTest(unittest.TestCase):
    """While grog plays music, it looks for its name in windows."""

    def test_what_it_hears_over_music_is_cut_into_overlapping_windows(self):
        stt = OverMusic(["la la la"], rest="la la")
        e = ears(mic=over_music(song(13.0), frames((".", 1.0))), stt=stt, wake=GROG, music=lambda: True)
        heard = e.listen()
        self.assertEqual(
            [(0, 124), (94, 218), (188, 312), (282, 405)],  # the last one ends in silence
            [numbered(pcm) for pcm in stt.windows],
        )
        for a, b in itertools.pairwise(stt.windows):
            self.assertAlmostEqual(1.0, (numbered(a)[1] - numbered(b)[0] + 1) * FRAME_S, delta=0.01)
        # What came before the last window was let go of as it went on (and
        # before the next one, once the last was transcribed, if that was
        # before the end).
        self.assertIn(numbered(heard.pcm), [(376, 405), (282, 405)])
        self.assertLess(heard.seconds, 4.2)
        self.assertTrue(heard.anywhere)
        self.assertIsNone(e.address(heard))
        self.assertEqual(([], [], ["listening"]), (e.chimes, e.heard_reports, e.told), "nobody talked to it")

    def test_its_name_in_the_second_window_wakes_it_and_what_follows_is_asked(self):
        stt = OverMusic(["la la la", "la la Grog, que horas"], rest="la Grog, que horas são?")
        mic = over_music(song(7.5), frames((".", 1.0)))
        e = ears(mic=mic, stt=stt, wake=GROG, music=lambda: True, captions=True)
        heard = e.listen()
        self.assertEqual(2, len(stt.windows))
        self.assertEqual(94, numbered(heard.pcm)[0], "from the window with its name")
        self.assertEqual("Grog, que horas são?", heard.text, "from its name on")
        self.assertEqual(["listening", "hearing", "answering"], e.told)
        self.assertEqual(["listening", "ack"], e.chimes, "its name while it went on, then got it")
        self.assertEqual("que horas são?", e.address(heard))
        self.assertEqual(
            [("Grog, que horas", False), ("Grog, que horas são?", False), ("Grog, que horas são?", True)],
            e.heard_reports,
            "never what came before its name",
        )

    def test_its_name_at_the_end_of_a_window(self):
        # Someone says its name and waits, over the music.
        stt = OverMusic(["la la la Grog"], rest="la Grog.")
        e = ears(mic=over_music(song(5.0), frames((".", 1.5))), stt=stt, wake=GROG, music=lambda: True)
        heard = e.listen()
        self.assertEqual("", e.address(heard), "just its name")
        self.assertEqual(["listening"], e.chimes)

    def test_once_it_heard_its_name_it_ends_as_usual(self):
        # Then the music is ducked: no more windows, and what follows ends at
        # a pause, or maxUtteranceSeconds after the window with its name.
        stt = OverMusic(["la la Grog, toca"], rest="Grog, toca outra")
        e = ears(mic=over_music(song(25.0)), stt=stt, wake=GROG, music=lambda: True)
        heard = e.listen()
        self.assertEqual(1, len(stt.windows))
        self.assertAlmostEqual(15.0, heard.seconds, delta=0.05)
        self.assertEqual("toca outra", e.address(heard))

    def test_without_music_as_usual(self):
        stt = OverMusic(["Grog, toca"], rest="la la la")
        e = ears(mic=over_music(song(20.0)), stt=stt, wake=GROG)
        heard = e.listen()
        self.assertEqual([], stt.windows)
        self.assertAlmostEqual(15.0, heard.seconds, delta=0.05, msg="maxUtteranceSeconds")
        self.assertFalse(heard.anywhere)
        self.assertIsNone(e.address(heard))

    def test_once_awake_as_usual(self):
        stt = OverMusic(["la la la"], rest="toca outra")
        e = ears(mic=over_music(song(20.0)), stt=stt, wake=GROG, awake="awake", music=lambda: True)
        heard = e.listen()
        self.assertEqual([], stt.windows)
        self.assertAlmostEqual(15.0, heard.seconds, delta=0.05)
        self.assertEqual("toca outra", e.address(heard))

    def test_music_stopping_brings_it_back_to_usual_at_once(self):
        playing = [True]

        class Stopping(FakeMic):
            def read(self, timeout=None):
                if self.read_count == 200:
                    playing[0] = False
                return super().read(timeout)

        stt = OverMusic(["la la la"], rest="la la")
        mic = Stopping(song(25.0), frame_wait_s=0.002)
        e = ears(mic=mic, stt=stt, wake=GROG, music=lambda: playing[0])
        heard = e.listen()
        self.assertEqual([(0, 124)], [numbered(pcm) for pcm in stt.windows], "none after it stopped")
        self.assertAlmostEqual(15.0, heard.seconds, delta=0.05, msg="then maxUtteranceSeconds")
        self.assertEqual(94, numbered(heard.pcm)[0])
        # Then an utterance as usual: its name first.
        e.mic.frames = frames(("S", 1.0), (".", 1.0))
        e.stt = FakeStt("Grog, que horas são?")
        heard = e.listen()
        self.assertEqual((False, "que horas são?"), (heard.anywhere, e.address(heard)))

    def test_partials_only_until_the_first_window(self):
        # They'd only cost CPU: the windows look for its name from then on.
        partials = Saying("la " * 60)
        stt = OverMusic(["la la la"], rest="la la")
        mic = over_music(song(13.0), frames((".", 1.0)))
        e = ears(mic=mic, stt=stt, partials=partials, wake=GROG, music=lambda: True, settings=PARTIAL)
        e.listen()
        self.assertEqual(4, len(stt.windows))
        self.assertTrue(partials.calls)
        self.assertLessEqual(max(seconds(pcm) for pcm in partials.calls), ears_module.WAKE_WINDOW_S)
        self.assertLessEqual(len(partials.calls), 6)

    def test_its_name_is_found_anywhere_in_a_window_only(self):
        e = ears(wake=GROG)
        self.assertIsNone(e.command("la la la Grog, que horas", needs_name=True))
        self.assertEqual("que horas", e.command("la la la Grog, que horas", needs_name=True, anywhere=True))


class PartialsTest(unittest.TestCase):
    """Partials: what was said so far, transcribed while they talk (partialMs)."""

    def test_hears_its_name_while_they_talk_on(self):
        said = "Grog, que horas são agora em Lisboa?"
        e = ears(mic=slow_mic(("S", 2.0), (".", 1.0)), stt=Saying(said), partials=Saying(said), wake=GROG)
        heard = e.listen()
        self.assertAlmostEqual(
            0.8, seconds(e.partial_stt.stt.calls[0]), delta=0.05, msg="what was said so far"
        )
        self.assertEqual(
            ["listening", "ack"], e.chimes, "its name while they talk on, got it as they're done"
        )
        self.assertEqual("que horas são agora em Lisboa?", e.address(heard))
        self.assertEqual(["listening", "hearing", "answering"], e.told)
        self.assertEqual([], e.heard_reports, "without captions, nothing")
        self.assertEqual(1, e.partial_stt.holds, "held until it listens again")
        with self.assertRaises(MicError):
            e.listen()
        self.assertEqual(0, e.partial_stt.holds)

    def test_someone_else_talking_is_transcribed_as_it_goes_on_only_at_first(self):
        said = "Droga, esqueci a chave do carro em cima da mesa da cozinha de novo hoje"
        partials = Saying(said)
        e = ears(mic=slow_mic(("S", 4.0), (".", 1.0)), stt=Saying(said), partials=partials, wake=GROG)
        self.assertIsNone(e.address(e.listen()))
        so_far = [seconds(pcm) for pcm in partials.calls]
        self.assertTrue(so_far, "as it went on")
        self.assertLessEqual(max(so_far), ears_module.WAKE_WINDOW_S, "then only the whole")
        self.assertEqual(([], [], ["listening"]), (e.chimes, e.heard_reports, e.told), "nobody talked to it")

    def test_what_is_said_to_it_comes_word_by_word_when_captions_show_it(self):
        said = "Grog, qual vai ser a previsão do tempo para amanhã em São Paulo?"
        for captions in (False, True):
            stt, partials = Saying(said, per_word_s=0.2), Saying(said, per_word_s=0.2)
            mic = slow_mic(("S", 3.0), (".", 1.0))
            e = ears(mic=mic, stt=stt, partials=partials, wake=GROG, captions=captions)
            self.assertEqual(said, e.listen().text)
            guesses = [text for text, final in e.heard_reports if not final]
            if not captions:
                self.assertEqual([], guesses, "never shown")
                self.assertLess(
                    max(seconds(pcm) for pcm in partials.calls), 2.0, "nor transcribed to its end"
                )
                continue
            self.assertGreater(len(guesses), 5)
            self.assertEqual("Grog, qual vai ser", guesses[0])
            self.assertEqual(said, guesses[-1])
            self.assertTrue(all(b.startswith(a) for a, b in itertools.pairwise(guesses)), guesses)

    def test_the_end_never_waits_for_what_was_said_so_far(self):
        said, holds, go_on, partial_done = "Grog, que horas são?", [], threading.Event(), threading.Event()

        def stt(pcm):
            holds.append(e.partial_stt.holds)
            return said

        def slow(pcm):
            go_on.wait(5)
            partial_done.set()
            return said

        e = ears(mic=slow_mic(("S", 1.5), (".", 1.0)), stt=stt, partials=slow, wake=GROG, settings=PARTIAL)
        self.assertEqual(said, e.listen().text)
        self.assertFalse(partial_done.is_set(), "it ended while a partial went on")
        go_on.set()
        self.assertTrue(holds and min(holds) >= 1, "held while the end is transcribed")
        self.assertEqual(1, e.partial_stt.holds, "and until it listens again")
        with self.assertRaises(MicError):
            e.listen()
        self.assertEqual(0, e.partial_stt.holds)

    def test_its_name_last_so_far_waits_for_more(self):
        # "Drog..." of "Droga": a name cut short, until more came.
        partials = FakeStt("Drog", "Droga, esqueci", "Droga, esqueci a chave.")
        stt = FakeStt("Droga, esqueci a chave.")
        e = ears(mic=slow_mic(("S", 1.5), (".", 1.0)), stt=stt, partials=partials, wake=GROG, captions=True)
        self.assertIsNone(e.address(e.listen()))
        self.assertEqual(([], [], ["listening"]), (e.chimes, e.heard_reports, e.told))

    def test_once_it_heard_its_name_it_answers(self):
        # The whole lost it: what was said, as is.
        partials, stt = FakeStt("Grog, que horas"), FakeStt("Droga, que horas são?")
        e = ears(mic=slow_mic(("S", 1.5), (".", 1.0)), stt=stt, partials=partials, wake=GROG, captions=True)
        self.assertEqual("Droga, que horas são?", e.address(e.listen()))
        self.assertEqual(("Droga, que horas são?", True), e.heard_reports[-1])

    def test_once_it_heard_its_name_an_empty_whole_is_the_guess(self):
        partials, stt = FakeStt("Grog, que horas"), FakeStt("")
        e = ears(mic=slow_mic(("S", 1.5), (".", 1.0)), stt=stt, partials=partials, wake=GROG, captions=True)
        heard = e.listen()
        self.assertEqual(("", "Grog, que horas"), (heard.text, heard.said))
        self.assertEqual("que horas", e.address(heard))
        self.assertEqual(("Grog, que horas", True), e.heard_reports[-1])

    def test_got_it_before_the_whole_is_transcribed(self):
        saying, acked, saw = Saying("Grog, que horas são?"), threading.Event(), []

        def stt(pcm):
            saw.append(acked.wait(5))  # the whole, or up to the pause: done once it said it got it
            return saying(pcm)

        e = ears(
            mic=slow_mic(("S", 1.5), (".", 1.0)),
            stt=stt,
            partials=Saying("Grog, que horas são?"),
            wake=GROG,
            settings=PARTIAL,
        )
        e.chime = lambda name: (e.chimes.append(name), name == "ack" and acked.set())
        heard = e.listen()
        self.assertTrue(saw and all(saw), "got it first")
        self.assertLess(heard.acked, heard.transcribed)
        self.assertEqual(["listening", "ack"], e.chimes)
        self.assertEqual(["listening", "hearing", "answering"], e.told)

    def test_closing_closes_the_mic_and_partials(self):
        e = ears(frames(), partials=FakeStt(""))
        e.close()
        self.assertTrue(e.mic.closed and e.partial_stt.closed)


if __name__ == "__main__":
    unittest.main()
