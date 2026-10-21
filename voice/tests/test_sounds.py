import array
import unittest

from grog_voice import sounds
from grog_voice.audio import peak_dbfs

EARCONS = [sounds.ack, sounds.listening, sounds.error, sounds.back]


class SoundsTest(unittest.TestCase):
    def test_short_quiet_and_without_clicks(self):
        for earcon in EARCONS:
            pcm = earcon(22050)
            samples = array.array("h", pcm)
            self.assertTrue(0.1 < len(samples) / 22050 < 0.5, earcon.__name__)
            self.assertTrue(-20 < peak_dbfs(pcm) < -12, f"{earcon.__name__}: {peak_dbfs(pcm):.1f} dBFS")
            self.assertLess(
                abs(samples[0]) + abs(samples[-1]), 100, f"{earcon.__name__} starts and ends silent"
            )

    def test_each_its_own(self):
        self.assertEqual(len(EARCONS), len({earcon(16000) for earcon in EARCONS}))

    def test_got_it_is_shorter_than_listening(self):
        # They often play a moment apart: the tick that says it got it
        # mustn't sound like another "listening".
        self.assertLess(len(sounds.ack(16000)), len(sounds.listening(16000)) * 0.6)

    def test_at_any_rate(self):
        self.assertAlmostEqual(len(sounds.ack(16000)) / 16000, len(sounds.ack(22050)) / 22050, places=2)


if __name__ == "__main__":
    unittest.main()
