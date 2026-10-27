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
        self.pocket = os.path.join(self.dir.name, "pocket")
        self.clone = os.path.join(self.dir.name, "mine.npz")
        files = (
            [self.voice, self.voice + ".json", self.style, self.clone]
            + [os.path.join(self.model, f) for f in PARAKEET_INT8]
            + [
                os.path.join(self.supertonic, f)
                for f in config.SUPERTONIC_FILES + ("voice_styles/M1.json", "voice_styles/F2.json")
            ]
            + [
                os.path.join(self.pocket, f)
                for f in config.POCKET_FILES + ("voices/deep-calm.npz", "voices/radio.npz")
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
        self.assertEqual(4, settings["musicUtteranceSeconds"])
        self.assertEqual(
            {
                "engine": "pocket",
                "voice": None,
                "speed": None,
                "model": None,
                "language": "pt",
                "steps": 5,
                "threads": 4,
            },
            settings["tts"],
        )
        self.assertEqual("deep-calm", config.voice(settings["tts"]))
        self.assertEqual(
            [
                "stt.model: expected a Parakeet model directory (encoder, decoder and joiner, .int8.onnx or "
                ".onnx, and tokens.txt), got nothing",
                "tts.model: expected a Pocket TTS model directory (flow_lm.onnx, mimi_decoder.onnx, "
                "tokenizer.model and pocket.json, as voice/tools/export_pocket.py makes them), got nothing",
            ],
            config.problems(settings),
        )

    def test_speech_to_text_int8_or_fp32(self):
        def problems(model):
            return config.problems(
                config.deep_merge(config.DEFAULTS, {"stt": {"model": model}, "tts": {"model": self.pocket}})
            )

        fp32 = os.path.join(self.dir.name, "fp32")
        os.mkdir(fp32)
        for f in ("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"):
            open(os.path.join(fp32, f), "w").close()
        self.assertEqual([], problems(self.model))
        self.assertEqual([], problems(fp32))
        partials = {
            "stt": {"model": fp32, "partialModel": self.model, "partialThreads": 1},
            "tts": {"model": self.pocket},
        }
        self.assertEqual([], config.problems(config.deep_merge(config.DEFAULTS, partials)), "int8 partials")
        wrong = {
            "stt": {"model": fp32, "partialModel": self.dir.name, "partialThreads": 0},
            "tts": {"model": self.pocket},
        }
        self.assertEqual(
            [
                "stt.partialModel: expected a Parakeet model directory, or null, "
                f"got {json.dumps(self.dir.name)}",
                "stt.partialThreads: expected a positive integer, got 0",
            ],
            config.problems(config.deep_merge(config.DEFAULTS, wrong)),
        )
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
                    "tts": {"model": self.pocket},
                    "mic": "my-mic",
                    "endSilenceMs": 800,
                }
            )
        )
        settings = config.load(path)
        self.assertEqual([], config.problems(settings))
        self.assertEqual(2, settings["stt"]["threads"])
        self.assertEqual("pocket", settings["tts"]["engine"])
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

        pocket = {"model": self.pocket}
        self.assertEqual(
            [], problems({**pocket, "voice": "radio", "threads": 6, "steps": 0, "language": "xx"})
        )
        self.assertEqual([], problems({**pocket, "voice": self.clone}), "a voice of its own")
        self.assertEqual(
            [
                "tts.voice: expected one of its model's voices (deep-calm, radio), or a voice file (.npz), "
                'got "M1"',
                "tts.threads: expected a positive integer, got 0",
            ],
            problems({**pocket, "voice": "M1", "threads": 0}),
        )
        self.assertEqual(
            [
                "tts.voice: expected one of its model's voices (in its voices/), or a voice file (.npz), "
                f"got {json.dumps(os.path.join(self.dir.name, 'missing.npz'))}",
                "tts.model: expected a Pocket TTS model directory (flow_lm.onnx, mimi_decoder.onnx, "
                "tokenizer.model and pocket.json, as voice/tools/export_pocket.py makes them), "
                f"got {json.dumps(self.supertonic)}",
            ],
            problems({"model": self.supertonic, "voice": os.path.join(self.dir.name, "missing.npz")}),
        )
        model = {"engine": "supertonic", "model": self.supertonic}
        self.assertEqual([], problems(model), "M1, its own default")
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
            ["tts.voice: expected a Piper voice (.onnx, with its .onnx.json next to it), got nothing"],
            problems({"engine": "piper"}),
        )
        self.assertEqual(
            ['tts.engine: expected "pocket", "supertonic" or "piper", got "espeak"'],
            problems({"engine": "espeak"}),
        )

    def test_speed(self):
        def problems(tts):
            return config.problems(
                config.deep_merge(config.DEFAULTS, {"stt": {"model": self.model}, "tts": tts})
            )

        for engine in (
            {"engine": "supertonic", "model": self.supertonic},
            {"engine": "piper", "voice": self.voice},
        ):
            for ok in (None, 0.5, 1, 1.25, 2):
                self.assertEqual([], problems({**engine, "speed": ok}), ok)
            for bad in (0, 0.4, 2.5, "1.2", True):
                self.assertEqual(
                    [f"tts.speed: expected a number from 0.5 to 2, or null, got {json.dumps(bad)}"],
                    problems({**engine, "speed": bad}),
                    bad,
                )
        self.assertEqual([], problems({"model": self.pocket, "speed": None}))
        self.assertEqual(
            ["tts.speed: expected null: Pocket TTS speaks at its voice's own pace, got 1.2"],
            problems({"model": self.pocket, "speed": 1.2}),
        )

    def test_music_windows(self):
        def problems(**kw):
            settings = config.deep_merge(
                config.DEFAULTS, {"stt": {"model": self.model}, "tts": {"model": self.pocket}, **kw}
            )
            return config.problems(settings)

        self.assertEqual(4, config.DEFAULTS["musicUtteranceSeconds"])
        for ok in (2, 2.5, 4, 15):
            self.assertEqual([], problems(musicUtteranceSeconds=ok), ok)
        self.assertEqual([], problems(musicUtteranceSeconds=20, maxUtteranceSeconds=20))
        for bad in (0, 1.5, 16, "4", True, None):
            self.assertEqual(
                [
                    "musicUtteranceSeconds: expected a number from 2 to maxUtteranceSeconds (15), "
                    f"got {'nothing' if bad is None else json.dumps(bad)}"
                ],
                problems(musicUtteranceSeconds=bad),
                bad,
            )
        self.assertEqual(
            [
                "maxUtteranceSeconds: expected a positive number, got 0",
                "musicUtteranceSeconds: expected a number, 2 or more, got 1",
            ],
            problems(musicUtteranceSeconds=1, maxUtteranceSeconds=0),
        )

    def test_the_respeakers_front(self):
        def problems(front):
            models = {"stt": {"model": self.model}, "tts": {"model": self.pocket}}
            return config.problems(config.deep_merge(config.DEFAULTS, {**models, "ring": {"front": front}}))

        self.assertIsNone(config.DEFAULTS["ring"]["front"])
        for front in (None, 0, 180, 359.5):
            self.assertEqual([], problems(front), front)
        for front in (360, -1, "180", True):
            expected = f"ring.front: expected an angle from 0 to 359, or null, got {json.dumps(front)}"
            self.assertEqual([expected], problems(front))

    def test_follow_ups(self):
        def problems(seconds):
            models = {"stt": {"model": self.model}, "tts": {"model": self.pocket}}
            return config.problems(config.deep_merge(config.DEFAULTS, {**models, "followUpSeconds": seconds}))

        self.assertEqual(8, config.DEFAULTS["followUpSeconds"])
        for ok in (0, 2.5, 8, 30):
            self.assertEqual([], problems(ok), ok)
        for bad in (-1, "8", True, None):
            got = "nothing" if bad is None else json.dumps(bad)
            self.assertEqual(
                [f"followUpSeconds: expected a number, 0 or more, got {got}"], problems(bad), bad
            )

    def test_fillers(self):
        def problems(fillers):
            models = {"stt": {"model": self.model}, "tts": {"model": self.pocket}}
            return config.problems(config.deep_merge(config.DEFAULTS, {**models, "fillers": fillers}))

        self.assertIs(True, config.DEFAULTS["fillers"])
        for ok in [
            True,
            False,
            {},
            {"search": ["Pesquisando."]},
            {"search": [], "check": ["Já vejo.", "Hm."]},
        ]:
            self.assertEqual([], problems(ok), ok)
        for bad in [
            None,
            "Buscando.",
            ["Buscando."],
            {"act": ["Feito."]},
            {"search": "Buscando."},
            {"check": [""]},
        ]:
            self.assertEqual(
                [
                    'fillers: expected true, false, or phrases by kind, like {"search": ["Buscando."], '
                    f'"check": ["Checando."]}}, got {"nothing" if bad is None else json.dumps(bad)}'
                ],
                problems(bad),
                bad,
            )

    def test_wake_words(self):
        def problems(wake_word):
            settings = config.deep_merge(
                config.DEFAULTS,
                {"stt": {"model": self.model}, "tts": {"model": self.pocket}, "wakeWord": wake_word},
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
