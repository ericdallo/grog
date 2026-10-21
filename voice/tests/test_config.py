import json
import os
import tempfile
import unittest

from grog_voice import config


class ConfigTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.model = os.path.join(self.dir.name, "parakeet")
        os.mkdir(self.model)
        self.voice = os.path.join(self.dir.name, "voice.onnx")
        for path in (self.voice, self.voice + ".json"):
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
            [
                "stt.model: expected a Parakeet model directory, got nothing",
                "tts.voice: expected a Piper voice (.onnx, with its .onnx.json next to it), got nothing",
            ],
            config.problems(settings),
        )

    def test_file_merges_over_the_defaults(self):
        path = self.write(
            json.dumps(
                {
                    "stt": {"model": self.model},
                    "tts": {"voice": self.voice},
                    "mic": "my-mic",
                    "endSilenceMs": 800,
                }
            )
        )
        settings = config.load(path)
        self.assertEqual([], config.problems(settings))
        self.assertEqual(2, settings["stt"]["threads"])
        self.assertEqual("my-mic", settings["mic"])
        self.assertEqual(800, settings["endSilenceMs"])
        self.assertIsNone(config.DEFAULTS["mic"], "the defaults stay untouched")

    def test_problems_name_the_settings_as_written(self):
        settings = config.load(
            self.write(
                json.dumps(
                    {
                        "stt": {"model": self.model, "threads": True, "beam": 2},
                        "tts": {"voice": os.path.join(self.dir.name, "missing.onnx")},
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

    def test_wake_words(self):
        def problems(wake_word):
            settings = config.deep_merge(
                config.DEFAULTS,
                {"stt": {"model": self.model}, "tts": {"voice": self.voice}, "wakeWord": wake_word},
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
