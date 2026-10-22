import os
import stat
import threading
import unittest
from unittest import mock

from fakes import SECRET_ARTIST, SECRET_QUERY, SECRET_TITLE, FakeClients, FakeSpawn, temp_dir

from grog_music import config, main
from grog_music.server import ask


class MpvSocketTest(unittest.TestCase):
    def test_private_in_the_runtime_dir(self):
        runtime = temp_dir(self)
        path = main.mpv_socket({"mpvSocket": None}, {"XDG_RUNTIME_DIR": runtime})
        self.assertEqual(os.path.join(runtime, "grog-music", "mpv.sock"), path)
        self.assertEqual(0o700, stat.S_IMODE(os.stat(os.path.dirname(path)).st_mode))

    def test_set(self):
        self.assertEqual("/x/mpv.sock", main.mpv_socket({"mpvSocket": "/x/mpv.sock"}, {}))


class ServeTest(unittest.TestCase):
    """grog-music as a whole, with a fake mpv and fake YouTube Music."""

    def setUp(self):
        d = temp_dir(self)
        self.settings = config.deep_merge(
            config.DEFAULTS,
            {"socket": os.path.join(d, "music.sock"), "mpvSocket": os.path.join(d, "mpv.sock")},
        )
        self.spawn, self.clients = FakeSpawn(), FakeClients()
        built = main.build(self.settings, spawn=self.spawn, client=self.clients)
        for patch in (
            mock.patch.object(main, "build", return_value=built),
            mock.patch.object(main, "WATCH_EVERY_S", 0.02),
        ):
            patch.start()
            self.addCleanup(patch.stop)
        stop = threading.Event()
        self.thread = threading.Thread(target=main.serve, args=(self.settings, stop), daemon=True)
        self.thread.start()
        self.addCleanup(self.thread.join, 5)
        self.addCleanup(stop.set)
        for _ in range(500):
            if os.path.exists(self.settings["socket"]):
                break
            stop.wait(0.01)
        self.assertTrue(os.path.exists(self.settings["socket"]))

    def ask(self, **request):
        return ask(self.settings["socket"], request)

    def test_plays_what_youtube_music_finds_and_logs_none_of_it(self):
        with self.assertLogs("grog_music", "INFO") as logs:
            reply = self.ask(command="youtube-music.play", query=SECRET_QUERY, kind="song")
            status = self.ask(command="status")
            self.ask(command="duck")
            self.ask(command="youtube-music.search", query=SECRET_QUERY)
        self.assertTrue(reply["ok"])
        self.assertEqual({"title": SECRET_TITLE, "artist": SECRET_ARTIST}, reply["playing"])
        self.assertEqual(3, reply["count"])
        self.assertEqual("playing", status["state"])
        self.assertEqual(
            "ytdl://https://music.youtube.com/watch?v=aaaaaaaaaaa", self.spawn.mpv.asked("loadfile")[0][1]
        )
        self.assertEqual(20, self.spawn.mpv.props["volume"])
        text = "\n".join(logs.output)
        for secret in (SECRET_QUERY, SECRET_TITLE, SECRET_ARTIST):
            self.assertNotIn(secret, text)
        self.assertRegex(text, r"youtube-music\.play: ok in \d+ ms")

    def test_mpv_started_again_when_it_dies(self):
        self.ask(command="play", items=[{"url": "file:///a.mp3"}])
        self.spawn.mpv.die()
        for _ in range(500):
            if len(self.spawn.started) == 2:
                break
            threading.Event().wait(0.01)
        self.assertEqual(2, len(self.spawn.started))
        self.assertEqual("idle", self.ask(command="status")["state"])

    def test_auth_required(self):
        self.assertEqual(
            {"ok": False, "error": "that needs your YouTube Music account: connect it"},
            self.ask(command="youtube-music.library", kind="playlists"),
        )


if __name__ == "__main__":
    unittest.main()
