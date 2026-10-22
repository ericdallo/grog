import os
import unittest

from fakes import FakeSpawn, temp_dir

from grog_music import Refused
from grog_music.mpv import Mpv
from grog_music.player import Player, mpv_url, playable

SONGS = [
    {"url": "https://music.youtube.com/watch?v=aaaaaaaaaaa", "title": "One", "artist": "A", "album": "X"},
    {"url": "https://radio.example/stream.mp3"},
    {"url": "file:///music/three.flac", "title": "Three"},
]


class PlayerTest(unittest.TestCase):
    def setUp(self):
        self.spawn = FakeSpawn()
        self.player = Player(volume=80, duck_level=20, max_queue=5, shuffle=lambda items: items.reverse())
        self.player.mpv = Mpv(
            "mpv",
            os.path.join(temp_dir(self), "mpv.sock"),
            "yt-dlp",
            spawn=self.spawn,
            on_start=self.player.on_mpv_start,
        )
        self.addCleanup(self.player.mpv.close)
        self.player.mpv.ensure()

    @property
    def fake(self):
        return self.spawn.mpv

    def test_idle(self):
        self.assertEqual({"state": "idle", "volume": 80, "ducked": False, "count": 0}, self.player.status())
        # A new mpv gets the player's volume.
        self.assertEqual(80, self.fake.props["volume"])

    def test_play_replaces_the_queue_and_plays(self):
        self.fake.props["pause"] = True
        status = self.player.play(SONGS)
        self.assertEqual(
            [
                ["loadfile", "ytdl://https://music.youtube.com/watch?v=aaaaaaaaaaa", "replace"],
                ["loadfile", "https://radio.example/stream.mp3", "append"],
                ["loadfile", "file:///music/three.flac", "append"],
            ],
            self.fake.asked("loadfile"),
        )
        self.assertEqual(False, self.fake.props["pause"])
        self.assertEqual(
            {
                "state": "playing",
                "volume": 80,
                "ducked": False,
                "count": 3,
                "index": 0,
                "title": "One",
                "artist": "A",
                "album": "X",
            },
            status,
        )

    def test_shuffle(self):
        self.player.play(SONGS, shuffle=True)
        self.assertEqual("file:///music/three.flac", self.fake.playlist[0])
        self.assertEqual("Three", self.player.status()["title"])

    def test_queue_capped(self):
        self.player.play(SONGS * 3)
        self.assertEqual(5, len(self.fake.playlist))

    def test_what_mpv_knows_when_the_item_says_nothing(self):
        self.player.play(SONGS)
        self.fake.pos = 1
        self.fake.props |= {
            "metadata": {"icy-title": "Radio song"},
            "time-pos": 12.345,
            "duration": 200.0,
            "pause": True,
        }
        status = self.player.status()
        self.assertEqual("paused", status["state"])
        self.assertEqual("Radio song", status["title"])
        self.assertEqual(12.3, status["positionS"])
        self.assertEqual(200.0, status["durationS"])
        self.assertNotIn("artist", status)

    def test_enqueue(self):
        self.player.enqueue(SONGS[:1])
        self.assertEqual(["loadfile", mpv_url(SONGS[0]["url"]), "replace"], self.fake.asked("loadfile")[0])
        self.player.enqueue(SONGS[1:])
        self.assertEqual(
            ["loadfile", "https://radio.example/stream.mp3", "append-play"], self.fake.asked("loadfile")[1]
        )
        self.assertEqual(3, self.player.status()["count"])
        self.player.enqueue(SONGS * 2)
        self.assertEqual(5, self.player.status()["count"])
        with self.assertRaisesRegex(Refused, "full"):
            self.player.enqueue(SONGS)

    def test_pause_resume_stop(self):
        self.player.play(SONGS)
        self.assertEqual("paused", self.player.pause()["state"])
        self.assertEqual("playing", self.player.resume()["state"])
        status = self.player.stop()
        self.assertEqual(["stop"], self.fake.commands[-3])
        self.assertEqual("idle", status["state"])
        self.assertEqual([], self.player.items)
        with self.assertRaisesRegex(Refused, "nothing is playing"):
            self.player.resume()

    def test_next_and_previous(self):
        with self.assertRaisesRegex(Refused, "nothing is playing"):
            self.player.next()
        self.player.play(SONGS)
        self.assertEqual(None, self.player.next().get("title"))
        self.assertEqual(["playlist-next", "weak"], self.fake.asked("playlist-next")[0])
        self.assertEqual("Three", self.player.next()["title"])
        with self.assertRaisesRegex(Refused, "last one"):
            self.player.next()
        self.player.previous()
        self.assertEqual(["playlist-prev", "weak"], self.fake.asked("playlist-prev")[0])
        self.player.previous()
        with self.assertRaisesRegex(Refused, "first one"):
            self.player.previous()

    def test_volume(self):
        self.assertEqual(50, self.player.volume(level=50)["volume"])
        self.assertEqual(50, self.fake.props["volume"])
        self.assertEqual(60, self.player.volume(change=10)["volume"])
        self.assertEqual(0, self.player.volume(change=-100)["volume"])
        self.assertEqual(100, self.player.volume(change=300)["volume"])
        for bad in ({}, {"level": 5, "change": 1}, {"level": 101}, {"level": True}, {"change": 1.5}):
            with self.assertRaises(Refused):
                self.player.volume(**bad)

    def test_duck_and_unduck_are_idempotent(self):
        status = self.player.duck()
        self.assertEqual({"volume": 80, "ducked": True}, {k: status[k] for k in ("volume", "ducked")})
        self.assertEqual(20, self.fake.props["volume"])
        self.player.duck(level=5)
        self.assertEqual(20, self.fake.props["volume"])
        self.assertEqual(False, self.player.unduck()["ducked"])
        self.assertEqual(80, self.fake.props["volume"])
        self.player.unduck()
        self.assertEqual(80, self.fake.props["volume"])

    def test_volume_while_ducked_is_where_it_goes_back_to(self):
        self.player.duck(30)
        self.player.volume(level=60)
        self.assertEqual(30, self.fake.props["volume"])
        self.player.volume(level=10)
        self.assertEqual(10, self.fake.props["volume"])
        self.player.volume(change=40)
        self.assertEqual(30, self.fake.props["volume"])
        self.player.unduck()
        self.assertEqual(50, self.fake.props["volume"])

    def test_a_new_mpv_gets_the_volume_and_no_queue(self):
        self.player.play(SONGS)
        self.player.duck()
        self.fake.die()
        status = self.player.status()
        self.assertEqual(2, len(self.spawn.started))
        self.assertEqual(20, self.fake.props["volume"])
        self.assertEqual("idle", status["state"])
        self.assertEqual([], self.player.items)

    def test_bad_items(self):
        for items in (
            None,
            [],
            ["x"],
            [{"title": "no url"}],
            [{"url": "ftp://x/y"}],
            [{"url": "/etc/passwd"}],
            [{"url": "file://host/x"}],
            [{"url": "https://x/\n"}],
            [{"url": "ytdl://x"}],
        ):
            with self.assertRaises(Refused, msg=items):
                self.player.play(items)
        self.assertEqual([], self.fake.asked("loadfile"))


class UrlTest(unittest.TestCase):
    def test_playable(self):
        self.assertTrue(playable("https://music.youtube.com/watch?v=x"))
        self.assertTrue(playable("http://radio.example:8000/live"))
        self.assertTrue(playable("file:///home/a/song.mp3"))
        self.assertFalse(playable("https://"))
        self.assertFalse(playable("x" * 5000))

    def test_youtube_goes_to_yt_dlp(self):
        self.assertEqual("ytdl://https://youtu.be/abc", mpv_url("https://youtu.be/abc"))
        self.assertEqual(
            "ytdl://https://WWW.YouTube.com/watch?v=a", mpv_url("https://WWW.YouTube.com/watch?v=a")
        )
        self.assertEqual("https://radio.example/a", mpv_url("https://radio.example/a"))
        self.assertEqual("file:///a.mp3", mpv_url("file:///a.mp3"))


if __name__ == "__main__":
    unittest.main()
