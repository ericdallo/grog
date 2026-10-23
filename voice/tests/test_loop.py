import threading
import unittest
from unittest import mock

from fakes import (
    ACK,
    ANSWER,
    BACK,
    FRAME,
    FRAME_S,
    HEARD,
    LISTENING,
    PARTIAL,
    SETTINGS,
    FakeMic,
    FakeStt,
    FakeTts,
    FakeVad,
    Held,
    OverMusic,
    Players,
    Saying,
    answering,
    frames,
    hanging,
    slow_mic,
    song,
)

from grog_voice import loop
from grog_voice.audio import MicError
from grog_voice.loop import Voice
from grog_voice.wake import WakeWords

GROG = WakeWords(["Grog"])


def voice(
    mic_frames=(), stt=None, ask=None, settings=SETTINGS, wake=None, mic=None, partials=None, captions=False
):
    """A Voice with fakes, with partials by `partials` (a speech to text) when
    given; what it tells grog it's doing is in .reported, what it heard for
    grog in .heard_reports ((text, final)), its players in .players."""
    reported, heard, players = [], [], Players()
    v = Voice(
        settings,
        mic=mic or FakeMic(mic_frames),
        vad=FakeVad(),
        stt=stt or FakeStt(HEARD),
        tts=FakeTts(),
        player=players,
        ask=ask or answering("São dez ", "e quinze. Mais ", "alguma coisa?"),
        wake=lambda: wake,
        report=reported.append,
        report_heard=lambda text, final: heard.append((text, final)),
        captions=lambda: captions,
        partial_stt=Held(partials) if partials is not None else None,
        frame_bytes=FRAME,
        frame_s=FRAME_S,
    )
    v.reported, v.heard_reports, v.players = reported, heard, players
    return v


def written(v):
    return [p.written for p in v.players.made]


class TurnTest(unittest.TestCase):
    def test_a_turn(self):
        v = voice(mic=slow_mic(("S", 1.0), (".", 1.0)))
        result = v.turn()
        self.assertEqual(("answered", HEARD, HEARD), (result.kind, result.heard.text, result.command))
        self.assertEqual(ANSWER, result.turn.answer)
        self.assertEqual([[ACK], [t.encode() for t in v.mouth.tts.texts]], written(v))
        self.assertEqual(["listening", "hearing", "answering", "speaking"], v.reported, "what it told grog")

    def test_just_the_wake_word_wakes_it(self):
        v = voice(
            frames(("S", 0.5), (".", 1.5)),
            wake=GROG,
            stt=FakeStt("Grog."),
            settings={**SETTINGS, "speculateMs": 0},
        )
        result = v.turn()
        self.assertEqual(("woken", ""), (result.kind, result.command))
        self.assertEqual("awake", v.awake(), "what's said next needs no wake word")
        self.assertEqual([[LISTENING]], written(v), "its sound, played by then")

    def test_what_isnt_for_it_is_ignored(self):
        v = voice(frames(("S", 1.0), (".", 1.0)), wake=GROG, stt=FakeStt("Droga, esqueci."))
        result = v.turn()
        self.assertEqual(("ignored", None, None), (result.kind, result.command, result.turn))
        self.assertEqual([], written(v))

    def test_once_a_guess_heard_its_name_an_empty_whole_is_answered(self):
        partials, ask = FakeStt("Grog, que horas"), answering("Dez e quinze.")
        mic = slow_mic(("S", 1.5), (".", 1.0))
        v = voice(mic=mic, stt=FakeStt(""), partials=partials, ask=ask, wake=GROG, settings=PARTIAL)
        result = v.turn()
        self.assertEqual("answered", result.kind)
        self.assertEqual(["que horas"], ask.asked)
        self.assertEqual(["listening", "hearing", "answering", "speaking"], v.reported)


class RunTest(unittest.TestCase):
    def test_logs_how_what_it_ignored_sounded_never_its_words(self):
        utterances = frames(*[part for _ in range(4) for part in (("S", 1.0), (".", 1.0))])
        stt = FakeStt(HEARD, "Grog, que horas são?", "Droga, esqueci.", "")
        v = voice(utterances, ask=answering("Dez e quinze."), wake=GROG, stt=stt)
        with self.assertLogs("grog_voice", "INFO") as logs, self.assertRaises(MicError):
            v.run()
        ignored = [line for line in logs.output if "turn:" not in line and not line.endswith(":listening")]
        how = "1.0 s of it speech, peak -4 dBFS"
        self.assertEqual(
            [
                f"INFO:grog_voice:not for grog: 1.2 s, {how}",
                # With the 0.32 s of silence before them.
                f"INFO:grog_voice:not for grog: 1.5 s, {how}",
                f"INFO:grog_voice:no words heard: 1.5 s, {how}",
            ],
            ignored,
        )
        for words in ("que horas", "Droga", "esqueci", "quinze"):
            self.assertNotIn(words, "\n".join(logs.output))

    def test_answers_only_when_called(self):
        utterances = [part for _ in range(4) for part in (("S", 1.0), (".", 1.0))]
        ask = answering("Dez e quinze.")
        v = voice(
            mic=slow_mic(*utterances),
            ask=ask,
            wake=GROG,
            stt=FakeStt(HEARD, "Grog, que horas são?", "Droga, esqueci.", ""),
            captions=True,
        )
        with self.assertLogs("grog_voice", "INFO"), self.assertRaises(MicError):
            v.run()
        self.assertEqual(["que horas são?"], ask.asked, "without the wake word")
        self.assertEqual(["Grog, que horas são?"], ask.said, "with it, as captions show it")
        # Its name and what to do, heard at the pause, which was the end: got
        # it, then the answer. Nothing for the rest.
        self.assertEqual([[ACK], [b"Dez e quinze."]], written(v))
        self.assertEqual(
            [("Grog, que horas são?", False), ("Grog, que horas são?", True)],
            v.heard_reports,
            "only what's for it",
        )
        self.assertEqual(
            ["listening", "hearing", "answering", "speaking", "listening"],
            v.reported,
            "it heard who talked to it, not the others",
        )

    def test_its_name_at_a_pause_gets_a_sound_right_away(self):
        # "Grog... que horas são?": what's guessed at the pause starts with its name.
        mic = slow_mic(("S", 0.5), (".", 0.4), ("S", 1.0), (".", 1.0))
        ask = answering("Dez e quinze.")
        v = voice(mic=mic, stt=FakeStt("Grog.", "Grog, que horas são?"), ask=ask, wake=GROG)
        with self.assertLogs("grog_voice", "INFO"), self.assertRaises(MicError):
            v.run()
        self.assertEqual(["que horas são?"], ask.asked)
        self.assertEqual(
            [[LISTENING], [ACK], [b"Dez e quinze."]],
            written(v),
            "while they talk on, then got it as they're done, then the answer, once each",
        )
        self.assertEqual([], v.heard_reports, "without captions")

    def test_just_its_name_sounds_once_and_is_logged_by_how_it_sounded(self):
        v = voice(mic=slow_mic(("S", 0.5), (".", 1.5)), wake=GROG, stt=FakeStt("Grog."), captions=True)
        with self.assertLogs("grog_voice", "INFO") as logs, self.assertRaises(MicError):
            v.run()
        self.assertEqual([[LISTENING]], written(v))
        self.assertEqual([("Grog.", False), ("Grog.", True)], v.heard_reports)
        self.assertIn(
            "INFO:grog_voice:just the wake word: 0.7 s, 0.5 s of it speech, peak -4 dBFS", logs.output
        )
        self.assertNotIn("Grog", "\n".join(logs.output))

    def test_after_its_name_alone_what_comes_next_is_kept(self):
        # Its sound played at the pause, before the end: nothing is dropped.
        v = voice(mic=slow_mic(("S", 0.5), (".", 1.5)), wake=GROG, stt=FakeStt("Grog."))
        with self.assertLogs("grog_voice", "INFO"), self.assertRaises(MicError):
            v.run()
        self.assertEqual(([[LISTENING]], 0), (written(v), v.ears.mic.flushed))
        # Its name only known at the end: its sound plays then, and what the
        # mic heard over it is dropped.
        v = voice(
            frames(("S", 0.5), (".", 1.5)),
            wake=GROG,
            stt=FakeStt("Grog."),
            settings={**SETTINGS, "speculateMs": 0},
        )
        with self.assertLogs("grog_voice", "INFO"), self.assertRaises(MicError):
            v.run()
        self.assertEqual(([[LISTENING]], 1), (written(v), v.ears.mic.flushed))

    def test_a_reply_without_its_name_is_shown(self):
        mic = slow_mic(("S", 0.5), (".", 1.5), ("S", 1.0), (".", 1.0))
        stt = FakeStt("Grog.", "que horas são")
        v = voice(mic=mic, stt=stt, ask=answering("Dez e quinze."), wake=GROG, captions=True)
        with self.assertLogs("grog_voice", "INFO"), self.assertRaises(MicError):
            v.run()
        self.assertEqual(
            [("Grog.", False), ("Grog.", True), ("que horas são", False), ("que horas são", True)],
            v.heard_reports,
        )

    def test_just_the_wake_word_then_what_to_do(self):
        # A short utterance, like a name alone, waits longer for more (1.2 s).
        utterances = frames(("S", 0.5), (".", 1.5), ("S", 1.0), (".", 1.0), ("S", 1.0), (".", 1.0))
        ask = answering("Dez e quinze.")
        v = voice(utterances, ask=ask, wake=GROG, stt=FakeStt("Grog.", HEARD, "e amanhã?"))
        with self.assertLogs("grog_voice", "INFO"), self.assertRaises(MicError):
            v.run()
        self.assertEqual([HEARD], ask.asked, "the next thing said, once")
        self.assertEqual([LISTENING], v.players.made[0].written)
        self.assertEqual(ACK, v.players.made[1].written[0])
        self.assertEqual(
            ["listening", "hearing", "awake", "hearing", "answering", "speaking", "listening"],
            v.reported,
            "the third, without its name, isn't for it",
        )

    @mock.patch.object(loop, "AWAKE_S", 0.2)
    def test_awake_for_a_while(self):
        # Just its name, then 2 s of silence, at 5 ms a frame: 0.3 s.
        utterances = frames(("S", 0.5), (".", 1.5), (".", 2.0))
        v = voice(mic=FakeMic(utterances, frame_wait_s=0.005), wake=GROG, stt=FakeStt("Grog."))
        with self.assertLogs("grog_voice", "INFO"), self.assertRaises(MicError):
            v.run()
        self.assertEqual(["listening", "hearing", "awake", "listening"], v.reported)

    def test_the_reply_to_its_question_needs_no_wake_word(self):
        utterances = frames(*[part for _ in range(3) for part in (("S", 1.0), (".", 1.0))])
        answers = iter([["Vou limpar o lixo do Nix. Posso?"], ["Pronto."], ["Não era comigo."]])

        def ask(text, said=None, on_connect=None):
            ask.asked.append(text)
            yield from (("text", piece) for piece in next(answers))

        ask.asked = []
        v = voice(
            utterances, ask=ask, wake=GROG, stt=FakeStt("Grog, limpa o lixo do Nix.", "Pode.", "E agora?")
        )
        with self.assertLogs("grog_voice", "INFO"), self.assertRaises(MicError):
            v.run()
        # After its statement ("Pronto."), the wake word is needed again.
        self.assertEqual(["limpa o lixo do Nix.", "Pode."], ask.asked)
        self.assertEqual(
            ["listening", "hearing", "answering", "speaking"]
            + ["asked", "hearing", "answering", "speaking"]
            + ["listening"],
            v.reported,
            "it says it asked something",
        )

    @mock.patch.object(loop, "AWAKE_S", 0)
    def test_not_for_long(self):
        utterances = frames(("S", 0.5), (".", 1.5), ("S", 1.0), (".", 1.0))
        ask = answering("Dez e quinze.")
        v = voice(utterances, ask=ask, wake=GROG, stt=FakeStt("Grog.", HEARD))
        with self.assertLogs("grog_voice", "INFO"), self.assertRaises(MicError):
            v.run()
        self.assertEqual([], ask.asked)

    def test_run_logs_timings_never_words(self):
        mic_frames = frames(("S", 1.0), (".", 1.0), ("S", 1.0), (".", 1.0))
        stt = FakeStt(HEARD, RuntimeError(f"failed on {HEARD!r}"))
        v = voice(mic_frames, stt=stt, ask=answering(("tool", "web_search"), ANSWER))
        with self.assertLogs("grog_voice", "INFO") as logs, self.assertRaises(MicError):
            v.run()
        text = "\n".join(logs.output)
        self.assertIn("turn: 1.2 s of speech", text)
        self.assertIn("tools: web_search", text)
        self.assertIn("the turn failed: RuntimeError at", text)
        for words in (HEARD, "quinze", "alguma"):
            self.assertNotIn(words, text)
        self.assertEqual(2, v.ears.mic.flushed, "drops what it heard while answering")
        # The answer asked something: so it says, even after the failed turn.
        self.assertEqual(
            ["listening", "hearing", "answering", "speaking", "asked", "hearing", "error", "asked"],
            v.reported,
        )

    def test_hears_its_name_while_they_talk_on(self):
        said = "Grog, que horas são agora em Lisboa?"
        ask = answering("Dez e quinze.")
        v = voice(
            mic=slow_mic(("S", 2.0), (".", 1.0)),
            stt=Saying(said),
            partials=Saying(said),
            ask=ask,
            wake=GROG,
            settings=PARTIAL,
        )
        with self.assertLogs("grog_voice", "INFO"), self.assertRaises(MicError):
            v.run()
        self.assertEqual(["que horas são agora em Lisboa?"], ask.asked)
        self.assertEqual(
            [[LISTENING], [ACK], [b"Dez e quinze."]],
            written(v),
            "its name while they talk on, got it as they're done, then the answer",
        )
        self.assertEqual(["listening", "hearing", "answering", "speaking", "listening"], v.reported)
        self.assertEqual(0, v.ears.partial_stt.holds, "it went on once it listened again")


class MusicTest(unittest.TestCase):
    def test_over_music_its_name_in_a_window_is_answered_and_nothing_heard_is_logged(self):
        # Music, then its name over it, as the second window of what followed.
        music = [*song(9.0), *frames((".", 1.0)), *song(7.5, start=1000), *frames((".", 1.0))]
        stt = OverMusic(
            ["la la la", "Droga, esqueci", "la la la", "la la Grog, que horas"],
            rest=["Droga, esqueci a chave", "la Grog, que horas são?"],
        )
        ask = answering("Dez e quinze.")
        v = voice(mic=FakeMic(music, frame_wait_s=0.002), stt=stt, ask=ask, wake=GROG, captions=True)
        with self.assertLogs("grog_voice", "INFO") as logs, self.assertRaises(MicError):
            v.set_music(True)
            v.set_music(True)
            v.run()
        self.assertEqual(4, len(stt.windows))
        self.assertEqual(["que horas são?"], ask.asked)
        self.assertEqual(["Grog, que horas são?"], ask.said, "from its name on")
        self.assertEqual(
            [("Grog, que horas", False), ("Grog, que horas são?", False), ("Grog, que horas são?", True)],
            v.heard_reports,
        )
        self.assertEqual("INFO:grog_voice:music on", logs.output[0], "once")
        # The music alone: since the last window it cut, how it sounded.
        ignored = [line for line in logs.output if "not for grog" in line]
        self.assertEqual(1, len(ignored))
        self.assertTrue(
            ignored[0].startswith("INFO:grog_voice:not for grog: 3.2 s, 3.2 s of it speech, peak")
        )
        text = "\n".join(logs.output)
        for words in ("la la", "Droga", "esqueci", "chave", "que horas", "Grog,", "quinze"):
            self.assertNotIn(words, text)

    def test_music_off_is_logged_once(self):
        v = voice()
        with self.assertLogs("grog_voice", "INFO") as logs:
            v.set_music(False)
            v.set_music(True)
            v.set_music(False)
        self.assertEqual(["INFO:grog_voice:music on", "INFO:grog_voice:music off"], logs.output)
        self.assertFalse(v.ears.music())


class AskedByGrogTest(unittest.TestCase):
    def test_its_mic_off_and_on_again(self):
        v = voice()
        v.tell("listening")
        v.set_mic(False)
        self.assertEqual([False], v.ears.mic.switched)
        self.assertTrue(v.ears.forgetting, "what was being said is forgotten")
        self.assertEqual(["listening", "muted"], v.reported, "grog is told at once")
        for state in ("listening", "awake", "asked"):
            v.tell(state)
        self.assertEqual(["listening", "muted"], v.reported, "muted, whatever it waits for")
        v.set_mic(False)
        self.assertEqual([False], v.ears.mic.switched, "once")
        v.set_mic(True)
        self.assertEqual([False, True], v.ears.mic.switched)
        self.assertEqual(["listening", "muted", "listening"], v.reported)

    def test_switched_off_while_it_speaks_it_says_so_once_done(self):
        v = voice()
        v.tell("speaking")
        v.set_mic(False)
        self.assertEqual(["speaking"], v.reported)
        v.tell("listening")
        self.assertEqual(["speaking", "muted"], v.reported)

    def test_switched_off_while_someone_talks_it_says_so_at_once(self):
        # It hears nothing more, so it wouldn't say anything else.
        v = voice()
        v.tell("hearing")
        v.set_mic(False)
        self.assertEqual(["hearing", "muted"], v.reported)

    def test_listens_now_without_its_name(self):
        v = voice()
        with mock.patch.object(v.mouth, "stop", wraps=v.mouth.stop) as stop:
            v.listen_now()
        stop.assert_called_once()
        self.assertEqual("awake", v.awake())
        v.mouth.quiet()
        self.assertEqual([[LISTENING]], written(v), "its sound says it listens")

    def test_its_sound_says_grog_is_back(self):
        v = voice()
        with self.assertLogs("grog_voice", "INFO") as logs:
            v.back()
        v.mouth.quiet()
        self.assertEqual([[BACK]], written(v))
        self.assertEqual(["INFO:grog_voice:grog is back"], logs.output)

    def test_grog_back_again_a_moment_later_plays_nothing_more(self):
        v = voice()
        v.back()
        v.back()
        v.mouth.quiet()
        self.assertEqual([[BACK]], written(v))
        v._back_at -= loop.BACK_EVERY_S
        v.back()
        v.mouth.quiet()
        self.assertEqual([[BACK], [BACK]], written(v), "once it's been a while")

    def test_grog_back_while_it_answers_plays_nothing(self):
        v = voice()
        v.tell("speaking")
        v.back()
        v.mouth.quiet()
        self.assertEqual([], written(v))

    def test_a_question_cut_off_waits_for_its_name_again(self):
        ask = hanging("Quer que eu continue?")
        v = voice(mic=slow_mic(("S", 1.0), (".", 1.0)), ask=ask)

        def stop_once_asked():
            ask.answered.wait(5)
            v.stop()

        threading.Thread(target=stop_once_asked).start()
        result = v.turn()
        self.assertTrue(result.turn.stopped)
        self.assertIsNone(v.awake())
        self.assertTrue(ask.conn.hung_up.is_set())


class WatchedMic(FakeMic):
    """A FakeMic saying when there's no mic, and when it's back."""

    def __init__(self, missing):
        super().__init__([])
        self.missing = missing

    def watch(self, report):
        self.report = report
        if self.missing:
            report("no-mic")


class TellTest(unittest.TestCase):
    def test_tells_grog_only_what_changed(self):
        v = voice()
        for state in ("listening", "listening", "hearing", "hearing", "listening"):
            v.tell(state)
        self.assertEqual(["listening", "hearing", "listening"], v.reported)

    def test_what_the_mic_says_goes_through_it(self):
        mic = WatchedMic(missing=True)
        v = voice(mic=mic)
        self.assertEqual(["no-mic"], v.reported, "no mic from the start")
        v.tell("listening")
        self.assertEqual(["no-mic"], v.reported, "not while there's none")
        mic.report("listening")
        v.tell("listening")
        self.assertEqual(["no-mic", "listening"], v.reported, "and it knows it's back")
        mic.report("no-mic")
        self.assertEqual("no-mic", v.told)

    def test_no_mic_whatever_it_waits_for(self):
        v = voice(mic=WatchedMic(missing=True))
        for state in ("listening", "awake", "asked"):
            v.tell(state)
        self.assertEqual(["no-mic"], v.reported)

    def test_closing_closes_the_mic_and_partials(self):
        v = voice(partials=FakeStt(""))
        v.close()
        self.assertTrue(v.ears.mic.closed and v.ears.partial_stt.closed)


if __name__ == "__main__":
    unittest.main()
