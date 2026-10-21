import json
import os
import tempfile
import unittest

from grog_voice import config

PARAKEET_INT8 = ("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")


class ConfigTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.model = os.path.join(self.dir.name, "parakeet")
        self.voice = os.path.join(self.dir.name, "voice.onnx")
        self.supertonic = os.path.join(self.dir.name, "supertonic-3")
        self.style = os.path.join(self.dir.name, "mine.json")
        files = (
            [self.voice, self.voice + ".json", self.style]
            + [os.path.join(self.model, f) for f in PARAKEET_INT8]
            + [
                os.path.join(self.supertonic, f)
                for f in config.SUPERTONIC_FILES + ("voice_styles/M1.json", "voice_styles/F2.json")
            ]
        )
        for path in files:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            open(path, "w").close()

    def write(self, text):
        path = os.path.join(self.dir.name, "settings.json")
        with open(path, "w") as f:
            f.write(text)
        return path

    def test_defaults_need_the_models(self):
        settings = config.load(None)
        self.assertEqual("http://127.0.0.1:8484", settings["grogUrl"])
        self.assertEqual(2, settings["stt"]["threads"])
        self.assertEqual(300, settings["partialMs"])
        self.assertEqual(
            {
                "engine": "supertonic",
                "voice": "M1",
                "model": None,
                "language": "pt",
                "steps": 5,
                "threads": 4,
            },
            settings["tts"],
        )
        self.assertEqual(
            [
                "stt.model: expected a Parakeet model directory (encoder, decoder and joiner, .int8.onnx or "
                ".onnx, and tokens.txt), got nothing",
                "tts.model: expected a Supertonic model directory (onnx/ and voice_styles/, "
                "as in Supertone/supertonic-3), got nothing",
            ],
            config.problems(settings),
        )

    def test_speech_to_text_int8_or_fp32(self):
        def problems(model):
            return config.problems(
                config.deep_merge(
                    config.DEFAULTS, {"stt": {"model": model}, "tts": {"model": self.supertonic}}
                )
            )

        fp32 = os.path.join(self.dir.name, "fp32")
        os.mkdir(fp32)
        for f in ("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"):
            open(os.path.join(fp32, f), "w").close()
        self.assertEqual([], problems(self.model))
        self.assertEqual([], problems(fp32))
        os.remove(os.path.join(fp32, "joiner.onnx"))
        self.assertEqual(
            [
                "stt.model: expected a Parakeet model directory (encoder, decoder and joiner, .int8.onnx or "
                f".onnx, and tokens.txt), got {json.dumps(fp32)}"
            ],
            problems(fp32),
            "a graph missing",
        )

    def test_file_merges_over_the_defaults(self):
        path = self.write(
            json.dumps(
                {
                    "stt": {"model": self.model},
                    "tts": {"model": self.supertonic},
                    "mic": "my-mic",
                    "endSilenceMs": 800,
                }
            )
        )
        settings = config.load(path)
        self.assertEqual([], config.problems(settings))
        self.assertEqual(2, settings["stt"]["threads"])
        self.assertEqual("M1", settings["tts"]["voice"])
        self.assertEqual("my-mic", settings["mic"])
        self.assertEqual(800, settings["endSilenceMs"])
        self.assertIsNone(config.DEFAULTS["mic"], "the defaults stay untouched")

    def test_problems_name_the_settings_as_written(self):
        settings = config.load(
            self.write(
                json.dumps(
                    {
                        "stt": {"model": self.model, "threads": True, "beam": 2},
                        "tts": {"engine": "piper", "voice": os.path.join(self.dir.name, "missing.onnx")},
                        "grogUrl": "localhost:8484",
                        "speculateMs": 0,
                        "endSilenceMs": "600",
                        "partialMs": -1,
                        "hotword": "hey",
                    }
                )
            )
        )
        self.assertEqual(
            [
                "unknown setting: stt.beam",
                "unknown setting: hotword",
                'grogUrl: expected a URL, like "http://127.0.0.1:8484", got "localhost:8484"',
                "stt.threads: expected a positive integer, got true",
                f"tts.voice: expected a Piper voice (.onnx, with its .onnx.json next to it), "
                f"got {json.dumps(os.path.join(self.dir.name, 'missing.onnx'))}",
                'endSilenceMs: expected a positive integer, got "600"',
                "partialMs: expected an integer, 0 or more, got -1",
            ],
            config.problems(settings),
        )

    def test_text_to_speech_by_engine(self):
        def problems(tts):
            return config.problems(
                config.deep_merge(config.DEFAULTS, {"stt": {"model": self.model}, "tts": tts})
            )

        model = {"model": self.supertonic}
        self.assertEqual([], problems({**model, "voice": "F2", "language": "en", "steps": 3, "threads": 2}))
        self.assertEqual([], problems({**model, "voice": self.style}), "a voice style of its own")
        self.assertEqual(
            [
                "tts.voice: expected one of Supertonic's voices (F1 to F5, M1 to M5), or a voice style file "
                '(.json), got "M3"',
                'tts.language: expected a language Supertonic reads, like "pt", got "xx"',
                "tts.steps: expected a positive integer, got 0",
                'tts.threads: expected a positive integer, got "4"',
            ],
            problems({**model, "voice": "M3", "language": "xx", "steps": 0, "threads": "4"}),
            "M3 isn't in this model",
        )
        self.assertEqual(
            [], problems({"engine": "piper", "voice": self.voice, "language": "xx"}), "its own only"
        )
        self.assertEqual(
            ['tts.voice: expected a Piper voice (.onnx, with its .onnx.json next to it), got "M1"'],
            problems({"engine": "piper"}),
        )
        self.assertEqual(
            ['tts.engine: expected "supertonic" or "piper", got "espeak"'], problems({"engine": "espeak"})
        )

    def test_wake_words(self):
        def problems(wake_word):
            settings = config.deep_merge(
                config.DEFAULTS,
                {"stt": {"model": self.model}, "tts": {"model": self.supertonic}, "wakeWord": wake_word},
            )
            return config.problems(settings)

        for ok in [None, False, "Grog", ["Grog", "Ei Grog"]]:
            self.assertEqual([], problems(ok), ok)
        for bad in [True, "", " ", [], ["Grog", ""], 3]:
            self.assertEqual(
                [f"wakeWord: expected a phrase, a list of phrases, false or null, got {json.dumps(bad)}"],
                problems(bad),
                bad,
            )

    def test_unusable_files(self):
        with self.assertRaisesRegex(config.ConfigError, "Couldn't read the settings file"):
            config.load(os.path.join(self.dir.name, "nope.json"))
        with self.assertRaisesRegex(config.ConfigError, "isn't valid JSON"):
            config.load(self.write("{"))
        with self.assertRaisesRegex(config.ConfigError, "must hold a JSON object"):
            config.load(self.write("[]"))


if __name__ == "__main__":
    unittest.main()
