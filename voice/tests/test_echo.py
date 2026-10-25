import unittest

from grog_voice import echo as echo_module
from grog_voice.audio import rms_dbfs
from grog_voice.echo import Echo

FRAME_S = 0.032


def level(db):
    """A frame of 512 samples at `db` dBFS RMS (at most 32767)."""
    a = min(32767, round(32768 * 10 ** (db / 20)))
    return (a.to_bytes(2, "little", signed=True) + (-a).to_bytes(2, "little", signed=True)) * 256


class LevelTest(unittest.TestCase):
    def test_rms(self):
        self.assertAlmostEqual(-40.0, rms_dbfs(level(-40)), delta=0.05)
        self.assertEqual(float("-inf"), rms_dbfs(bytes(1024)))
        self.assertEqual(float("-inf"), rms_dbfs(b""))


class EchoTest(unittest.TestCase):
    def setUp(self):
        self.now = 100.0
        self.echo = Echo(FRAME_S, clock=lambda: self.now)

    def test_it_knows_when_grog_played(self):
        self.assertFalse(self.echo.playing(self.now))
        self.echo.played(2.0)
        self.now += 1.0
        self.echo.played(0.5)  # after what's still playing
        self.assertTrue(self.echo.playing(100.0))
        self.assertTrue(self.echo.playing(102.5 + echo_module.TAIL_S), "and for its echo")
        self.assertFalse(self.echo.playing(102.6 + echo_module.TAIL_S))
        self.assertFalse(self.echo.playing(99.9))
        self.now = 110.0
        self.echo.played(0.1)  # after a silence: on its own
        self.assertFalse(self.echo.playing(105.0))
        self.assertTrue(self.echo.playing(110.05))

    def test_what_isnt_heard_over_grog_is_as_is(self):
        self.assertEqual(0.9, self.echo.gate(self.now, level(-40), 0.9))
        self.assertIsNone(self.echo.limit, "nothing learned")

    def test_it_knows_when_grog_spoke(self):
        self.echo.played(0.5)  # an earcon
        self.echo.played(2.0, speech=True)  # after it
        self.assertFalse(self.echo.speaking(100.2))
        self.assertTrue(self.echo.speaking(100.6))
        self.assertTrue(self.echo.speaking(102.5))
        self.assertFalse(self.echo.speaking(102.6), "not its echo")
        self.assertTrue(self.echo.playing(102.6))

    def test_its_echo_doesnt_count_as_speech_and_someone_louder_does(self):
        self.echo.played(10.0, speech=True)
        learn = round(echo_module.LEARN_S / FRAME_S)
        self.assertEqual(0.0, self.echo.gate(self.now, level(0), 0.9), "even loud, before it knows its echo")
        for _ in range(learn * 3):
            self.assertEqual(0.0, self.echo.gate(self.now, level(-30), 0.9))
        # Its echo, loudest at -30 dBFS: someone 6 dB louder than that is heard.
        self.assertAlmostEqual(-24.0, self.echo.limit, delta=0.1)
        self.assertEqual(0.0, self.echo.gate(self.now, level(-25), 0.9))
        self.assertEqual(0.9, self.echo.gate(self.now, level(-20), 0.9))
        self.assertEqual(0.1, self.echo.gate(self.now, level(-20), 0.1), "what isn't speech stays so")

    def test_its_loudest_is_learned_as_it_plays(self):
        self.echo.played(20.0, speech=True)
        # Quiet, with a pause now and then: its loudest decides.
        for i in range(300):
            self.echo.gate(self.now, level(-60 if i % 10 == 0 else -40 if i % 2 else -35), 0.9)
        self.assertAlmostEqual(-29.0, self.echo.limit, delta=0.1)
        # Louder now (its volume went up): learned within seconds.
        for _ in range(100):
            self.echo.gate(self.now, level(-10), 0.9)
        self.assertAlmostEqual(-4.0, self.echo.limit, delta=0.1)
        # Kept between answers, once it no longer plays.
        self.now += 30.0
        self.assertEqual(0.9, self.echo.gate(self.now, level(-30), 0.9))
        self.assertAlmostEqual(-4.0, self.echo.limit, delta=0.1)

    def test_its_earcons_teach_it_nothing(self):
        # Before it speaks: the echo of its earcons, quieter (the first
        # answer once it started, say). Nothing is heard over them meanwhile.
        self.echo.played(3.0)
        for _ in range(100):
            self.assertEqual(0.0, self.echo.gate(self.now + 1, level(-30), 0.9))
        self.assertIsNone(self.echo.limit)
        self.echo.played(20.0, speech=True)
        for _ in range(round(echo_module.LEARN_S / FRAME_S)):
            self.echo.gate(self.now + 5, level(-10), 0.9)
        self.assertAlmostEqual(-4.0, self.echo.limit, delta=0.1)
        self.assertEqual(0.0, self.echo.gate(self.now + 5, level(-8), 0.9), "its voice, a little louder now")

    def test_a_quiet_echo_is_learned_too(self):
        # Its speaker farther from its mic: its voice, quiet there, may not
        # even sound like speech to the VAD. Someone nearer is heard over it.
        self.echo.played(20.0, speech=True)
        for i in range(100):
            self.echo.gate(self.now, level(-46 if i % 2 else -52), 0.1)
        self.assertAlmostEqual(-40.0, self.echo.limit, delta=0.1)
        self.assertEqual(0.9, self.echo.gate(self.now, level(-30), 0.9))

    def test_grog_clipping_the_mic_leaves_nobody_heard_over_it(self):
        # Its speaker right next to its mic, as on a laptop: its voice at -8
        # to -15 dBFS there, someone 1 to 2 m away at -30 or so (plans/voice.md).
        self.echo.played(20.0, speech=True)
        for i in range(200):
            self.echo.gate(self.now, level(-8 if i % 3 else -15), 0.9)
        self.assertEqual(0.0, self.echo.gate(self.now, level(-30), 0.9))
        self.assertEqual(0.0, self.echo.gate(self.now, level(-10), 0.9), "even shouting")


if __name__ == "__main__":
    unittest.main()
