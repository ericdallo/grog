import os
import unittest

from fakes import FakeSpawn, temp_dir

from grog_music.mpv import Mpv, MpvError


class MpvTest(unittest.TestCase):
    def setUp(self):
        self.socket = os.path.join(temp_dir(self), "mpv.sock")
        self.spawn = FakeSpawn()
        self.started = []
        self.mpv = Mpv(
            "mpv", self.socket, "/bin/yt-dlp", spawn=self.spawn, on_start=lambda: self.started.append(1)
        )
        self.addCleanup(self.mpv.close)

    def test_starts_mpv_idle_audio_only_with_yt_dlp_and_nothing_on_the_terminal(self):
        self.mpv.ensure()
        argv = self.spawn.argv[0]
        self.assertEqual("mpv", argv[0])
        for arg in (
            "--idle=yes",
            "--no-video",
            "--no-terminal",
            f"--input-ipc-server={self.socket}",
            "--ytdl-format=bestaudio/best",
            "--script-opts=ytdl_hook-ytdl_path=/bin/yt-dlp",
        ):
            self.assertIn(arg, argv)
        self.assertEqual([1], self.started)

    def test_commands_get_their_reply_past_events(self):
        self.mpv.set("volume", 40)
        self.assertEqual(40, self.mpv.get("volume"))
        self.assertIsNone(self.mpv.get("media-title"))
        self.assertEqual(["set_property", "volume", 40], self.spawn.mpv.commands[0])

    def test_refusals_are_errors(self):
        with self.assertRaises(MpvError):
            self.mpv.command("playlist-next", "weak")

    def test_started_again_when_it_dies(self):
        self.mpv.ensure()
        self.spawn.mpv.die()
        self.mpv.set("pause", True)
        self.assertEqual(2, len(self.spawn.started))
        self.assertEqual([1, 1], self.started)
        self.assertEqual(True, self.spawn.mpv.props["pause"])

    def test_ensure_starts_it_again_when_it_died(self):
        self.mpv.ensure()
        self.mpv.ensure()
        self.assertEqual(1, len(self.spawn.started))
        self.spawn.mpv.die()
        self.mpv.ensure()
        self.assertEqual(2, len(self.spawn.started))

    def test_without_mpv(self):
        mpv = Mpv("mpv", self.socket, "yt-dlp", spawn=FakeSpawn(fail=FileNotFoundError(2, "no mpv")))
        with self.assertRaisesRegex(MpvError, "couldn't start mpv"):
            mpv.command("stop")

    def test_close_quits_it(self):
        self.mpv.ensure()
        self.mpv.close()
        self.assertEqual(["quit"], self.spawn.mpv.commands[-1])
        self.assertIsNotNone(self.spawn.mpv.poll())


if __name__ == "__main__":
    unittest.main()
