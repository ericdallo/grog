import threading
import time
import unittest

from fakes import (
    ACK,
    ANSWER,
    ERROR,
    HEARD,
    LISTENING,
    PAUSE,
    SILENCE,
    SOUND,
    SPEECH,
    FakeConnection,
    FakeTts,
    PausingTts,
    Players,
    StreamingTts,
    answering,
    hanging,
    played,
)

from grog_voice.audio import Silence
from grog_voice.ears import Heard
from grog_voice.grog import GrogError
from grog_voice.mouth import Mouth, Pauses, Turn


def heard(text=HEARD, **more):
    """What was heard: a second of speech, transcribed during the pause."""
    now = time.monotonic()
    pcm = SPEECH * 31 + SILENCE * 6
    fields = {"seconds": 1.184, "speech_s": 1.0, "speech_end": now, "transcribed": now, "speculated": True}
    return Heard(pcm=pcm, text=text, **(fields | more))


def mouth(ask=None, captions=False, players=None):
    """A Mouth with fakes: what it tells grog is in .told."""
    told = []
    m = Mouth(
        tts=FakeTts(),
        player=players or Players(),
        ask=ask or answering("São dez ", "e quinze. Mais ", "alguma coisa?"),
        tell=told.append,
        captions=lambda: captions,
    )
    m.told = told
    return m


class TurnTest(unittest.TestCase):
    def test_the_turn_line_says_how_much_louder_it_was_transcribed(self):
        turn = Turn(speech_end=0.0, speech_s=1.5, peak_db=-20.4, speculated=True)
        self.assertIn(
            "turn: 1.5 s of speech (peak -20 dBFS, transcribed 17 dB louder during the pause);", str(turn)
        )
        turn = Turn(speech_end=0.0, speech_s=1.5, peak_db=-20.4, speculated=False)
        self.assertIn("(peak -20 dBFS, transcribed 17 dB louder);", str(turn))
        turn = Turn(speech_end=0.0, speech_s=1.5, peak_db=-2.0, speculated=True)
        self.assertIn("(peak -2 dBFS, transcribed during the pause);", str(turn), "loud enough as is")
        turn = Turn(speech_end=0.0, speech_s=1.5, peak_db=-2.0, speculated=False)
        self.assertIn("(peak -2 dBFS);", str(turn))

    def test_the_turn_line_has_its_timings_in_the_order_they_came(self):
        turn = Turn(speech_end=10.0, speech_s=1.5, peak_db=-2.0, speculated=True)
        turn.marks.update(transcript=11.2, ack=10.6, first_text=12.0, first_audio=12.4, done=15.0)
        self.assertIn(
            "since it ended: got it 0.60 s, transcript 1.20 s, first words 2.00 s, speaking 2.40 s,"
            " done 5.00 s",
            str(turn),
        )


class PausesTest(unittest.TestCase):
    """At 8 samples a second: a second of sound is 16 bytes, Silence(8)
    half a second."""

    def setUp(self):
        self.now = 0.0
        self.pauses = Pauses(8, clock=lambda: self.now)

    def test_a_pause_plays_once_more_sound_comes(self):
        self.assertEqual([bytes(16)], self.pauses(bytes(16)))
        self.assertEqual([], self.pauses(Silence(8)), "held")
        self.now = 0.5
        self.assertEqual([bytes(8), b"ab"], self.pauses(b"ab"), "all of it: the second still plays")
        self.assertEqual([b"cd"], self.pauses(b"cd"))

    def test_what_was_waited_counts(self):
        self.pauses(bytes(16))
        self.pauses(Silence(8))
        self.now = 1.25
        self.assertEqual([bytes(4), b"ab"], self.pauses(b"ab"), "a quarter of a second already passed")
        self.pauses(Silence(8))
        self.now = 5.0
        self.assertEqual([b"cd"], self.pauses(b"cd"), "grog used a tool meanwhile")


class AnswerTest(unittest.TestCase):
    def test_speaks_the_answer_as_it_streams_in(self):
        m = mouth()
        turn = m.answer(heard(), HEARD)
        self.assertEqual(["São dez e quinze.", "Mais alguma coisa?"], m.tts.texts)
        [player] = m.player.made
        self.assertEqual(22050, player.rate)
        self.assertEqual([ACK] + [t.encode() for t in m.tts.texts], player.written, "got it, then the answer")
        self.assertTrue(player.closed)
        self.assertEqual(ANSWER, turn.answer)
        self.assertEqual(["ack"], turn.sounds)
        # Got it and grog's first words come as they may; then speaking, then done.
        marks = turn.marks
        self.assertEqual({"transcript", "ack", "first_text", "first_audio", "done"}, set(marks))
        self.assertLessEqual(marks["transcript"], marks["ack"])
        self.assertLessEqual(max(marks["ack"], marks["first_text"]), marks["first_audio"])
        self.assertLessEqual(marks["first_audio"], marks["done"])
        self.assertAlmostEqual(1.184, turn.speech_s)
        self.assertNotIn(HEARD, str(turn) + repr(turn))
        self.assertNotIn("quinze", str(turn) + repr(turn))
        self.assertEqual(["answering", "speaking"], m.told)
        self.assertEqual(([HEARD], [None]), (m.ask.asked, m.ask.said))

    def test_text_to_speech_that_streams_gets_whole_sentences_and_those_waiting_together(self):
        started, done = threading.Event(), threading.Event()

        def ask(text, said=None, on_connect=None):
            try:
                yield ("text", "Amanhã faz sol, com vento à tarde. ")
                # The rest comes while the first sentence is being said.
                started.wait(2)
                yield ("text", "Leve um casaco. ")
                yield ("text", "Até mais! ")
            finally:
                done.set()

        m = Mouth(tts=StreamingTts(started, done), player=Players(), ask=ask)
        m.answer(heard(), HEARD)
        self.assertEqual(["Amanhã faz sol, com vento à tarde.", "Leve um casaco. Até mais!"], m.tts.texts)
        self.assertEqual([ACK] + [t.encode() for t in m.tts.texts], played(m.player))

    def test_text_to_speech_that_streams_gets_a_short_first_sentence_with_the_next(self):
        m = Mouth(
            tts=StreamingTts(threading.Event(), threading.Event()),
            player=Players(),
            ask=answering("Claro! ", "Coloquei um alarme. "),
        )
        m.tts.go.set()
        m.answer(heard(), HEARD)
        self.assertEqual(["Claro! Coloquei um alarme."], m.tts.texts)
        self.assertEqual([ACK] + [t.encode() for t in m.tts.texts], played(m.player))

    def test_pauses_after_a_sentence_before_the_next_only(self):
        started, done = threading.Event(), threading.Event()

        def ask(text, said=None, on_connect=None):
            try:
                yield ("text", "Amanhã faz sol, com vento à tarde. ")
                started.wait(2)
                yield ("text", "Leve um casaco. ")
            finally:
                done.set()

        m = Mouth(tts=PausingTts(started, done), player=Players(), ask=ask)
        m.answer(heard(), HEARD)
        first, second = (text.encode() for text in m.tts.texts)
        self.assertEqual([ACK, first, SOUND, PAUSE, second, SOUND], played(m.player), "none at the end")

    def test_got_it_already_played_as_it_ended(self):
        now = time.monotonic()
        m = mouth(ask=answering("Dez e quinze."))
        h = heard("Grog, que horas são?", speech_end=now - 0.5, acked=now - 0.4, transcribed=now - 0.1)
        turn = m.answer(h, "que horas são?")
        self.assertEqual([[b"Dez e quinze."]], [p.written for p in m.player.made], "not again")
        self.assertEqual(["ack"], turn.sounds)
        self.assertRegex(str(turn), r"since it ended: got it [\d.]+ s, transcript [\d.]+ s, first words")

    def test_grog_sees_what_was_said_only_with_captions(self):
        for captions, said in ((False, None), (True, "Grog, que horas são?")):
            m = mouth(captions=captions, ask=answering("Dez e quinze."))
            m.answer(heard("Grog, que horas são?"), "que horas são?")
            self.assertEqual((["que horas são?"], [said]), (m.ask.asked, m.ask.said))
        m = mouth(captions=True, ask=answering("Dez e quinze."))
        m.answer(heard("", guess="Grog, que horas", addressed=True), "que horas")
        self.assertEqual(["Grog, que horas"], m.ask.said, "the guess, when the whole came out empty")

    def test_tools_have_no_sound(self):
        m = mouth(ask=answering(("tool", "web_search"), "Sol amanhã."))
        turn = m.answer(heard(), HEARD)
        self.assertEqual([ACK, b"Sol amanh\xc3\xa3."], played(m.player))
        self.assertEqual((["ack"], ["web_search"]), (turn.sounds, turn.tools))
        self.assertIn("tools: web_search", str(turn))

    def test_says_what_it_will_do_before_searching(self):
        said_meanwhile = []

        def ask(text, said=None, on_connect=None):
            yield ("text", "Vou ver o tempo.")
            yield ("tool", "web_search")
            # The search takes a while: what came before it is said meanwhile.
            deadline = time.monotonic() + 2
            while not m.tts.texts and time.monotonic() < deadline:
                time.sleep(0.01)
            said_meanwhile.extend(m.tts.texts)
            yield ("text", " Amanhã faz sol.")

        m = mouth(ask=ask)
        turn = m.answer(heard(), HEARD)
        self.assertEqual(["Vou ver o tempo."], said_meanwhile)
        self.assertEqual([ACK, b"Vou ver o tempo.", "Amanhã faz sol.".encode()], played(m.player))
        self.assertEqual((["ack"], ["web_search"]), (turn.sounds, turn.tools))

    def test_beeps_when_grog_cant_answer(self):
        m = mouth(ask=answering(GrogError("grog is down")))
        with self.assertLogs("grog_voice", "ERROR") as logs:
            turn = m.answer(heard(), HEARD)
        self.assertEqual([], m.tts.texts)
        self.assertEqual([ACK, ERROR], played(m.player))
        self.assertEqual(["ack", "error"], turn.sounds)
        self.assertEqual(["ERROR:grog_voice:grog couldn't answer: grog is down"], logs.output)
        self.assertEqual(["answering", "error"], m.told)

    def test_a_bug_while_answering_logs_no_words(self):
        m = mouth(ask=answering(ValueError(f"bad {HEARD!r}")))
        with self.assertLogs("grog_voice", "ERROR") as logs:
            m.answer(heard(), HEARD)
        [line] = logs.output
        self.assertRegex(line, r"grog couldn't answer: ValueError at .*fakes\.py:\d+$")
        self.assertNotIn(HEARD, line)
        self.assertEqual([ACK, ERROR], played(m.player))

    def test_speaks_what_came_before_failing(self):
        m = mouth(ask=answering("Primeira frase. Segunda", GrogError("cut off")))
        with self.assertLogs("grog_voice", "ERROR"):
            m.answer(heard(), HEARD)
        self.assertEqual(["Primeira frase.", "Segunda"], m.tts.texts)
        self.assertEqual(ERROR, played(m.player)[-1])


class StopTest(unittest.TestCase):
    def answer_until_stopped(self, m, before_stopping):
        """Starts answering, stops once `before_stopping` is set, and returns
        the turn."""
        result = []
        answering_ = threading.Thread(target=lambda: result.append(m.answer(heard(), HEARD)))
        answering_.start()
        self.assertTrue(before_stopping.wait(5))
        m.stop()
        answering_.join(5)
        self.assertFalse(answering_.is_alive())
        return result[0]

    def test_cuts_the_answer_off_and_hangs_up(self):
        ask = hanging("Primeira frase. ", "Segunda")
        m = mouth(ask=ask)
        turn = self.answer_until_stopped(m, ask.answered)
        self.assertTrue(ask.conn.hung_up.is_set(), "grog's turn stops too")
        self.assertTrue(turn.stopped)
        self.assertNotIn(ERROR, played(m.player), "it's no error")
        self.assertNotIn("error", m.told + turn.sounds)
        self.assertTrue(str(turn).endswith("; stopped"))

    def test_cuts_off_what_plays(self):
        playing = threading.Event()

        class Slow(Players):
            def __call__(self, rate):
                player = super().__call__(rate)
                write = player.write

                def slowly(pcm):
                    write(pcm)
                    playing.set()
                    # pw-cat takes its time: until it's stopped.
                    while not player.stopped:
                        time.sleep(0.01)

                player.write = slowly
                return player

        players = Slow()
        m = mouth(ask=hanging("Primeira frase. Segunda frase."), players=players)
        with self.assertNoLogs("grog_voice", "ERROR"):
            turn = self.answer_until_stopped(m, playing)
        self.assertTrue(turn.stopped)
        [player] = players.made
        self.assertTrue(player.stopped and player.closed)
        self.assertEqual([ACK], player.written, "what came after got it never played")

    def test_hangs_up_as_soon_as_connected_when_stopped_before(self):
        asked, connecting, conn = threading.Event(), threading.Event(), FakeConnection()

        def ask(text, said=None, on_connect=None):
            asked.set()
            # Stopped while it connects.
            connecting.wait(5)
            on_connect(conn)
            yield ("text", "Dez e quinze.")

        m = mouth(ask=ask)
        answering_ = threading.Thread(target=lambda: m.answer(heard(), HEARD))
        answering_.start()
        self.assertTrue(asked.wait(5))
        m.stop()
        connecting.set()
        answering_.join(5)
        self.assertTrue(conn.hung_up.is_set())
        self.assertNotIn(b"Dez e quinze.", played(m.player))

    def test_a_new_answer_isnt_stopped(self):
        m = mouth()
        m.stop()
        turn = m.answer(heard(), HEARD)
        self.assertFalse(turn.stopped)
        self.assertEqual(ANSWER, turn.answer)


class SoundsTest(unittest.TestCase):
    def test_chimes_play_one_after_the_other(self):
        m = mouth()
        m.chime("listening")
        m.chime("ack")
        m.quiet()
        self.assertEqual([[LISTENING], [ACK]], [p.written for p in m.player.made])
        self.assertTrue(all(p.closed for p in m.player.made))
        m.play("error")
        self.assertEqual(ERROR, played(m.player)[-1])

    def test_a_player_that_failed_is_closed(self):
        m = mouth(players=Players(failing=True))
        with self.assertRaises(BrokenPipeError):
            m.play("ack")
        with self.assertLogs("grog_voice", "ERROR") as logs:
            m.chime("listening")
            m.quiet()
        self.assertRegex(logs.output[0], r"playing failed: BrokenPipeError at ")
        self.assertEqual([True, True], [p.closed for p in m.player.made])

    def test_once_playing_failed_it_stops_synthesizing(self):
        players = Players(failing=True)

        def ask(text, said=None, on_connect=None):
            # Once the sound saying it got it failed to play.
            players.one_closed.wait(5)
            yield ("text", "Primeira frase. Segunda frase. ")
            yield ("text", "Terceira.")

        m = mouth(ask=ask, players=players)
        with self.assertLogs("grog_voice", "ERROR"):
            turn = m.answer(heard(), HEARD)
        self.assertEqual([], m.tts.texts)
        self.assertEqual("Primeira frase. Segunda frase. Terceira.", turn.answer, "it waited for the end")
        self.assertTrue(all(p.closed for p in players.made))


if __name__ == "__main__":
    unittest.main()
