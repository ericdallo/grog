import threading
import unittest

from fakes import FakeMic, FakeStt, FakeVad

from grog_voice import config
from grog_voice.ears import Ears, Heard
from grog_voice.grog import GrogError
from grog_voice.main import NameWatch, heard_line, text_to_speech, wake_words
from grog_voice.wake import WakeWords


def settings(wake_word):
    return config.deep_merge(config.DEFAULTS, {"wakeWord": wake_word})


class TextToSpeechTest(unittest.TestCase):
    def test_the_engine_the_settings_pick(self):
        made = []
        engines = {
            "supertonic": lambda *args: made.append(("supertonic", *args)),
            "piper": lambda *args: made.append(("piper", *args)),
        }
        tts = config.deep_merge(config.DEFAULTS, {"tts": {"model": "/m"}})["tts"]
        text_to_speech(tts, engines)
        mine = {"voice": "/voices/mine.json", "language": "en", "steps": 3, "speed": 1.3}
        text_to_speech({**tts, **mine}, engines)
        piper = {**tts, "engine": "piper", "voice": "/voices/faber.onnx"}
        text_to_speech(piper, engines)
        text_to_speech({**piper, "speed": 0.9}, engines)
        self.assertEqual(
            [
                ("supertonic", "/m", "/m/voice_styles/M1.json", "pt", 5, 4, 1.4),
                ("supertonic", "/m", "/voices/mine.json", "en", 3, 4, 1.3),
                ("piper", "/voices/faber.onnx", 1.0),
                ("piper", "/voices/faber.onnx", 0.9),
            ],
            made,
            "each engine at its own speed, unless set",
        )


class WakeWordsTest(unittest.TestCase):
    def test_from_the_settings(self):
        self.assertIsNone(wake_words(settings(False))(), "answers anything")
        self.assertEqual("x", wake_words(settings("Jarvis"))().match("Jarvis, x"))
        words = wake_words(settings(["Jarvis", "Computador"]))()
        self.assertEqual(("x", "y"), (words.match("Jarvis, x"), words.match("Computador, y")))

    def test_grogs_name_by_default(self):
        names = [None, "Grog", "Grog", "Jarvis"]
        with self.assertLogs("grog_voice", "INFO") as logs:
            wake = wake_words(settings(None), name=lambda: names.pop(0))
            self.assertIsNone(wake().match("Grog, x"), "nothing while grog didn't say its name")
            first = wake()
            self.assertEqual("x", first.match("Grog, x"))
            self.assertIs(first, wake(), "the same, while the name is")
            self.assertEqual("x", wake().match("Jarvis, x"), "it may change")
        self.assertEqual(
            ["INFO:grog_voice:answering to Grog", "INFO:grog_voice:answering to Jarvis"], logs.output
        )


class NameWatchTest(unittest.TestCase):
    def test_asks_grog_its_name_now_and_then(self):
        answers = iter([GrogError("down"), GrogError("down"), {"name": "Grog"}, {"name": "Jarvis"}])
        done = threading.Event()

        def status():
            answer = next(answers, None)
            if answer is None:  # the name changed, and it asked again
                done.set()
                return {"name": "Jarvis"}
            if isinstance(answer, Exception):
                raise answer
            return answer

        with self.assertLogs("grog_voice", "WARNING") as logs:
            watch = NameWatch(status, every_s=0.01, retry_s=0.01)
            self.addCleanup(watch.close)
            self.assertTrue(done.wait(5))
        self.assertEqual("Jarvis", watch())
        self.assertEqual(
            [
                "WARNING:grog_voice:couldn't ask grog its name, the wake word (down): "
                "answering nothing until it says"
            ],
            logs.output,
            "once while failing",
        )

    def test_none_before_grog_says(self):
        go_on = threading.Event()
        watch = NameWatch(lambda: go_on.wait(5) and {"name": "Grog"})
        self.addCleanup(watch.close)
        self.assertIsNone(watch())
        go_on.set()


def ears(wake):
    settings = {"endSilenceMs": 600, "speculateMs": 250, "partialMs": 0, "maxUtteranceSeconds": 15}
    e = Ears(settings, mic=FakeMic([]), vad=FakeVad(), stt=FakeStt(""), wake=lambda: wake)
    e.describe = lambda heard: "1.2 s, 1.0 s of it speech, peak -21 dBFS, transcribed 18 dB louder"
    return e


class ListenTest(unittest.TestCase):
    def test_what_it_heard_what_grog_would_do_and_how_it_sounded(self):
        how = "(1.2 s, 1.0 s of it speech, peak -21 dBFS, transcribed 18 dB louder)"
        grog = ears(WakeWords(["Grog"]))
        for text, line in [
            ("Grog, que horas são?", f"Grog, que horas são?  ->  asks: que horas são?  {how}"),
            ("Drog.", f"Drog.  ->  just the wake word  {how}"),
            ("Droga, esqueci.", f"Droga, esqueci.  ->  not for grog  {how}"),
            ("", f"…  ->  no words heard  {how}"),
        ]:
            self.assertEqual(line, heard_line(grog, Heard(pcm=b"", text=text, seconds=0.0)), text)
        anything = Heard(pcm=b"", text="Oi.", seconds=0.0, needs_name=False)
        self.assertEqual(f"Oi.  ->  answers anything  {how}", heard_line(ears(None), anything))

    def test_as_the_service_would(self):
        # A guess heard its name, which the whole lost: the service answers it.
        grog = ears(WakeWords(["Grog"]))
        heard = Heard(pcm=b"", text="Droga, que horas?", seconds=0.0, guess="Grog, que", addressed=True)
        self.assertIn("->  asks: Droga, que horas?", heard_line(grog, heard))
        heard = Heard(pcm=b"", text="", seconds=0.0, guess="Grog, que horas", addressed=True)
        self.assertIn("Grog, que horas  ->  asks: que horas", heard_line(grog, heard))


if __name__ == "__main__":
    unittest.main()
