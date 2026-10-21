import itertools
import threading
import unittest

from fakes import (
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
    Saying,
    frames,
    seconds,
    slow_mic,
)

from grog_voice import ears as ears_module
from grog_voice.audio import MicError
from grog_voice.ears import Ears, Heard
from grog_voice.wake import WakeWords

GROG = WakeWords(["Grog"])


class Told(list):
    """What it told grog it's doing, when that changed (as Voice does)."""

    def __call__(self, state):
        if not self or self[-1] != state:
            self.append(state)


def ears(
    mic_frames=(), *, mic=None, stt=None, wake=None, settings=None, partials=None, captions=False, awake=False
):
    """Ears with fakes, with partials by `partials` (a speech to text) when
    given; what they tell grog is in .told, the earcons they chime in
    .chimes, what they heard for grog in .heard_reports ((text, final))."""
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
        frame_bytes=FRAME,
        frame_s=FRAME_S,
    )
    e.told, e.chimes, e.heard_reports = told, chimes, heard
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
        e = ears(frames(("S", 1.0), (".", 1.0)), wake=GROG, awake=True, captions=True)
        heard = e.listen()
        self.assertEqual(HEARD, e.address(heard))
        self.assertEqual(["awake", "hearing", "answering"], e.told)
        self.assertEqual([(HEARD, False), (HEARD, True)], e.heard_reports)

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
