import os
import unittest

from fakes import FakeSpawn, temp_dir

from grog_music.mpv import MAX_TIMEOUTS, Mpv, MpvError, MpvTimeout


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
        self.assertFalse([a for a in argv if a.startswith("--audio-device")], "the default sink")

    def test_plays_where_told(self):
        mpv = Mpv("mpv", self.socket, "yt-dlp", spawn=self.spawn, audio_device="pipewire/grog-music-out")
        self.addCleanup(mpv.close)
        mpv.ensure()
        self.assertIn("--audio-device=pipewire/grog-music-out", self.spawn.argv[-1])

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
        with self.assertLogs("grog_music", "WARNING") as logs:
            self.mpv.set("pause", True)
        self.assertIn("starting it again", logs.output[0])
        self.assertEqual(2, len(self.spawn.started))
        self.assertEqual([1, 1], self.started)
        self.assertEqual(True, self.spawn.mpv.props["pause"])

    def test_started_again_when_the_connection_breaks(self):
        self.mpv.get("volume")
        self.spawn.mpv.hang_up()
        with self.assertLogs("grog_music", "WARNING") as logs:
            self.mpv.set("pause", True)
        self.assertIn("mpv went away", logs.output[0])
        self.assertEqual(2, len(self.spawn.started))
        self.assertEqual(True, self.spawn.mpv.props["pause"])

    def test_ensure_starts_it_again_when_it_died(self):
        self.mpv.ensure()
        self.mpv.ensure()
        self.assertEqual(1, len(self.spawn.started))
        self.spawn.mpv.die()
        self.mpv.ensure()
        self.assertEqual(2, len(self.spawn.started))

    def test_events_go_to_on_event_before_the_reply(self):
        events = []
        mpv = Mpv("mpv", self.socket, "yt-dlp", spawn=self.spawn, on_event=events.append)
        self.addCleanup(mpv.close)
        mpv.ensure()
        self.spawn.mpv.emit({"event": "end-file", "reason": "error", "playlist_entry_id": 1})
        self.assertEqual(100, mpv.get("volume"))
        self.assertEqual(
            [
                {"event": "property-change", "name": "volume"},
                {"event": "end-file", "reason": "error", "playlist_entry_id": 1},
            ],
            events,
        )

    def test_a_failing_on_event_doesnt_stop_the_reply(self):
        def fail(_):
            raise KeyError("x")

        mpv = Mpv("mpv", self.socket, "yt-dlp", spawn=self.spawn, on_event=fail)
        self.addCleanup(mpv.close)
        with self.assertLogs("grog_music", "WARNING"):
            self.assertEqual(100, mpv.get("volume"))

    def test_a_late_reply_is_an_error_for_that_command_only(self):
        mpv = Mpv("mpv", self.socket, "yt-dlp", spawn=self.spawn, reply_timeout_s=0.2)
        self.addCleanup(mpv.close)
        mpv.command("loadfile", "file:///a.mp3", "replace")
        self.spawn.mpv.silent = 1
        with self.assertRaises(MpvTimeout):
            mpv.get("volume")
        self.assertEqual(1, len(self.spawn.started))
        self.assertEqual(["file:///a.mp3"], self.spawn.mpv.playlist)
        self.assertEqual(100, mpv.get("volume"))
        # The count starts over with each reply.
        self.spawn.mpv.silent = MAX_TIMEOUTS - 1
        for _ in range(MAX_TIMEOUTS - 1):
            with self.assertRaises(MpvTimeout):
                mpv.get("volume")
        self.assertEqual(100, mpv.get("volume"))
        self.assertEqual(1, len(self.spawn.started))

    def test_started_again_when_it_stops_answering(self):
        mpv = Mpv("mpv", self.socket, "yt-dlp", spawn=self.spawn, reply_timeout_s=0.1)
        self.addCleanup(mpv.close)
        mpv.ensure()
        self.spawn.mpv.silent = 100
        for _ in range(MAX_TIMEOUTS - 1):
            with self.assertRaises(MpvTimeout):
                mpv.get("volume")
        with self.assertLogs("grog_music", "WARNING"):
            self.assertEqual(100, mpv.get("volume"))
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
