import os
import queue
import subprocess
import sys
import tempfile
import time
import unittest

from grog_voice.audio import FRAME_BYTES, Mic, Player, _pw_cat, duration, peak_dbfs, stt_gain_db


class MicTest(unittest.TestCase):
    def test_tries_again_until_there_is_a_mic(self):
        d = tempfile.TemporaryDirectory()
        self.addCleanup(d.cleanup)
        plugged = os.path.join(d.name, "plugged")
        # Like pw-cat without a mic, until one is "plugged in": then it records.
        script = (
            f"if [ -e {plugged} ]; then head -c {FRAME_BYTES * 2} /dev/zero; sleep 60; "
            f'else echo "stream error: no target node available" >&2; exit 1; fi'
        )
        reported = []
        with self.assertLogs("grog_voice", "INFO") as logs:
            mic = Mic(argv=["sh", "-c", script], retry_min_s=0.02, retry_max_s=0.05)
            self.addCleanup(mic.close)
            mic.watch(reported.append)
            with self.assertRaises(queue.Empty):
                mic.frames.get(timeout=0.5)  # several tries, no frames
            open(plugged, "w").close()
            _, frame = mic.frames.get(timeout=5)
        self.assertEqual(bytes(FRAME_BYTES), frame)
        warning = (
            "WARNING:grog_voice:no mic to record from (pw-cat exited with 1: "
            "stream error: no target node available): trying again until there's one"
        )
        self.assertEqual(
            [warning, "INFO:grog_voice:recording again"], logs.output, "says it once, not at every try"
        )
        self.assertEqual(["no-mic", "listening"], reported, "and tells grog, once")

    def test_switched_off_it_records_nothing(self):
        # Like pw-cat: a frame every 10 ms, until it's stopped.
        argv = [
            sys.executable,
            "-c",
            f"import sys, time\nwhile True:\n sys.stdout.buffer.write(bytes({FRAME_BYTES}))\n"
            " sys.stdout.flush()\n time.sleep(0.01)",
        ]
        reported = []
        mic = Mic(argv=argv, retry_min_s=0.02, on=False)
        self.addCleanup(mic.close)
        mic.watch(reported.append)
        with self.assertRaises(queue.Empty):
            mic.frames.get(timeout=0.3)
        mic.switch(True)
        mic.frames.get(timeout=5)
        recording = mic.proc
        mic.switch(False)
        recording.wait(5)
        time.sleep(0.1)  # what it printed before it went is read
        mic.flush()
        with self.assertRaises(queue.Empty):
            mic.frames.get(timeout=0.3)
        self.assertEqual([], reported, "not a mic gone")
        mic.switch(True)
        mic.frames.get(timeout=5)
        self.assertIsNot(recording, mic.proc, "pw-cat again")


class PlayerTest(unittest.TestCase):
    def test_pw_cat_records_and_plays(self):
        self.assertEqual(
            "pw-cat --record --raw --rate 16000 --channels 1 --format s16 --latency 32ms -",
            " ".join(_pw_cat("--record", 16000, "32ms", None)),
        )
        self.assertEqual(
            "pw-cat --playback --raw --rate 22050 --channels 1 --format s16 --latency 50ms --target bt -",
            " ".join(_pw_cat("--playback", 22050, "50ms", "bt")),
        )

    def test_stopping_a_player_cuts_it_off(self):
        player = Player.__new__(Player)
        player.rate, player.proc = 16000, subprocess.Popen(["sleep", "60"], stdin=subprocess.PIPE)
        player.stop()
        with self.assertRaises(BrokenPipeError):
            player.write(bytes(65536 * 4))
        player.close()
        self.assertIsNotNone(player.proc.returncode)

    def test_closing_a_player_whose_pw_cat_is_gone(self):
        player = Player.__new__(Player)
        player.rate, player.proc = 16000, subprocess.Popen(["true"], stdin=subprocess.PIPE)
        player.proc.wait()
        with self.assertRaises(BrokenPipeError):
            player.write(bytes(65536 * 4))
        player.close()
        self.assertEqual(0, player.proc.returncode)


class LevelsTest(unittest.TestCase):
    def test_duration(self):
        self.assertEqual(0.064, duration(bytes(FRAME_BYTES * 2)))
        self.assertEqual(0.5, duration(b"SSSS", frame_bytes=4, frame_s=0.5))

    def test_peak(self):
        self.assertEqual(float("-inf"), peak_dbfs(bytes(8)))
        self.assertAlmostEqual(0.0, peak_dbfs((-32768).to_bytes(2, "little", signed=True)), places=3)
        self.assertAlmostEqual(-6.02, peak_dbfs((16384).to_bytes(2, "little", signed=True)), places=2)

    def test_quiet_speech_is_transcribed_louder(self):
        self.assertEqual(21.0, stt_gain_db(-24.0), "to peak at -3 dBFS")
        self.assertEqual(0.0, stt_gain_db(-3.0))
        self.assertEqual(0.0, stt_gain_db(0.0), "loud speech stays as it is")
        self.assertEqual(30.0, stt_gain_db(-60.0), "30 dB louder at most")
        self.assertEqual(30.0, stt_gain_db(float("-inf")))


if __name__ == "__main__":
    unittest.main()
