import json
import os
import tempfile
import unittest

from grog_music import config


class ConfigTest(unittest.TestCase):
    def write(self, text):
        d = tempfile.TemporaryDirectory()
        self.addCleanup(d.cleanup)
        path = os.path.join(d.name, "settings.json")
        with open(path, "w") as f:
            f.write(text)
        return path

    def test_defaults_need_a_socket(self):
        settings = config.load(None)
        self.assertEqual("mpv", settings["mpv"])
        self.assertEqual(20, settings["duckLevel"])
        self.assertEqual(["socket: expected a path (or --socket), got nothing"], config.problems(settings))
        self.assertEqual([], config.problems(settings | {"socket": "/run/grog-music/music.sock"}))

    def test_merged_over_the_defaults(self):
        path = self.write(json.dumps({"socket": "/s", "youtubeMusic": {"location": "BR"}}))
        settings = config.load(path)
        self.assertEqual({"language": "en", "location": "BR"}, settings["youtubeMusic"])
        self.assertEqual([], config.problems(settings))

    def test_problems(self):
        settings = config.deep_merge(
            config.DEFAULTS,
            {
                "socket": "/s",
                "volume": 120,
                "maxQueue": 0,
                "youtubeMusic": {"language": "xx", "location": "br"},
                "colour": "red",
            },
        )
        problems = config.problems(settings)
        self.assertEqual("unknown setting: colour", problems[0])
        self.assertEqual(
            ["volume", "maxQueue", "youtubeMusic.language", "youtubeMusic.location"],
            [p.split(":")[0] for p in problems[1:]],
        )

    def test_bad_files(self):
        for text in ("{", "[]"):
            with self.assertRaises(config.ConfigError):
                config.load(self.write(text))
        with self.assertRaises(config.ConfigError):
            config.load("/nonexistent/settings.json")


if __name__ == "__main__":
    unittest.main()
