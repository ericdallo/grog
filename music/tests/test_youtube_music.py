import unittest

from fakes import (
    ALBUM_ID,
    ARTIST_ID,
    AUTH,
    PLAYLIST_ID,
    SECRET_ARTIST,
    SECRET_QUERY,
    SECRET_TITLE,
    SONG_ID,
    FakeClients,
    FakeGoogle,
    FakePlayer,
    FakeYTMusic,
)

from grog_music import Refused
from grog_music.youtube_music import (
    ACCOUNT_RETRY_S,
    NEEDS_ACCOUNT,
    YouTubeMusic,
    account,
    best_match,
    kind_of_id,
    normalized,
    token,
)


def watch(video_id):
    return f"https://music.youtube.com/watch?v={video_id}"


class ProviderTest(unittest.TestCase):
    def setUp(self):
        self.now = 1000.0
        self.player, self.google = FakePlayer(), FakeGoogle()
        self.clients = FakeClients()
        self.music = self.provider(self.clients)

    def provider(self, clients):
        return YouTubeMusic(
            self.player,
            clients,
            google=self.google,
            max_queue=100,
            clock=lambda: self.now,
            wall=lambda: 1_800_000_000,
        )

    @property
    def queue(self):
        return self.player.played[-1][0]

    def urls(self):
        return [i["url"] for i in self.queue]


class SearchTest(ProviderTest):
    def test_top(self):
        results = self.music.search(SECRET_QUERY)["results"]
        self.assertEqual(
            [
                {"kind": "artist", "id": ARTIST_ID, "title": SECRET_ARTIST},
                {
                    "kind": "song",
                    "id": SONG_ID,
                    "title": SECRET_TITLE,
                    "artist": SECRET_ARTIST,
                    "album": "Album",
                    "durationS": 200,
                },
            ],
            results,
        )
        self.assertEqual(("search", SECRET_QUERY, None), self.clients.anonymous.calls[0])

    def test_kinds(self):
        self.assertEqual(10, len(self.music.search("x", "songs")["results"]))
        self.assertEqual(("search", "x", "songs"), self.clients.anonymous.calls[-1])
        album = self.music.search("x", "albums")["results"][0]
        self.assertEqual({"kind": "album", "id": ALBUM_ID, "title": "Calm", "artist": SECRET_ARTIST}, album)
        playlist = self.music.search("x", "playlists")["results"][0]
        self.assertEqual(
            {"kind": "playlist", "id": PLAYLIST_ID, "title": "Calm mix", "artist": "Someone"}, playlist
        )
        self.assertEqual("artist", self.music.search("x", "artists")["results"][0]["kind"])

    def test_one_anonymous_client(self):
        self.music.search("a")
        self.music.search("b")
        self.assertEqual(1, self.clients.made)

    def test_bad(self):
        for query, kind in ((None, "top"), ("  ", "top"), ("x", "podcasts")):
            with self.assertRaises(Refused):
                self.music.search(query, kind)

    def test_youtube_music_failing(self):
        clients = FakeClients(anonymous=FakeYTMusic(refuse=True))
        with self.assertRaisesRegex(Refused, "YouTube Music didn't answer that"):
            self.provider(clients).search("x")


class PlayTest(ProviderTest):
    def test_a_song_then_its_up_next(self):
        reply = self.music.play(query=SECRET_QUERY, kind="song")
        self.assertEqual([watch(SONG_ID), watch("next0000001"), watch("next0000002")], self.urls())
        self.assertEqual(
            {"url": watch(SONG_ID), "title": SECRET_TITLE, "artist": SECRET_ARTIST}, self.queue[0]
        )
        self.assertEqual(("get_watch_playlist", SONG_ID, 100), self.clients.anonymous.calls[-1])
        self.assertEqual({"title": SECRET_TITLE, "artist": SECRET_ARTIST}, reply["playing"])
        self.assertEqual(3, reply["count"])
        self.assertEqual("playing", reply["state"])

    def test_a_bare_query_plays_the_top_result(self):
        self.music.play(query=SECRET_QUERY)
        self.assertEqual(("get_artist", ARTIST_ID), self.clients.anonymous.calls[1])
        self.assertEqual(("get_playlist", "VLOLAKtopsongs", 100), self.clients.anonymous.calls[2])
        self.assertEqual(100, len(self.queue))

    def test_plural_kinds_too(self):
        self.music.play(query="x", kind="songs")
        self.assertEqual(("search", "x", "songs"), self.clients.anonymous.calls[0])

    def test_an_album(self):
        self.music.play(query="calm", kind="album")
        self.assertEqual(
            [
                {
                    "url": watch(f"album{i:06d}"),
                    "title": f"Track {i}",
                    "artist": SECRET_ARTIST,
                    "album": "Calm",
                }
                for i in range(1, 4)
            ],
            self.queue,
        )

    def test_a_playlist_capped(self):
        reply = self.music.play(query="calm", kind="playlist", shuffle=True)
        self.assertEqual(("get_playlist", PLAYLIST_ID, 100), self.clients.anonymous.calls[-1])
        self.assertEqual(100, len(self.queue))
        self.assertTrue(self.player.played[-1][1])
        self.assertEqual(100, reply["count"])

    def test_by_id(self):
        self.music.play(id=ALBUM_ID)
        self.assertEqual(("get_album", ALBUM_ID), self.clients.anonymous.calls[-1])
        self.music.play(id="bbbbbbbbbbb")
        self.assertEqual(("get_watch_playlist", "bbbbbbbbbbb", 100), self.clients.anonymous.calls[-1])
        self.music.play(id="PLx", kind="playlist")
        self.assertEqual(("get_playlist", "PLx", 100), self.clients.anonymous.calls[-1])
        self.assertEqual(3, len([c for c in self.clients.anonymous.calls if c[0] != "search"]))
        self.assertFalse([c for c in self.clients.anonymous.calls if c[0] == "search"])

    def test_nothing_found(self):
        self.clients.anonymous.search = lambda q, filter=None: []
        with self.assertRaisesRegex(Refused, "^nothing found for that$"):
            self.music.play(query="x")
        self.assertEqual([], self.player.played)

    def test_what_to_play(self):
        for args in ({}, {"query": " "}, {"kind": "podcast", "query": "x"}, {"id": 3}, {"id": "x!"}):
            with self.assertRaises(Refused, msg=args):
                self.music.play(**args)
        self.assertEqual([], self.player.played)

    def test_anonymous_even_with_an_account(self):
        self.music.play(query="x", kind="song", auth=AUTH)
        self.assertEqual([], self.clients.tokens)

    def test_bad_auth(self):
        with self.assertRaisesRegex(Refused, "auth needs"):
            self.music.play(query="x", auth={"accessToken": "a"})


class AccountTest(ProviderTest):
    def test_needs_an_account(self):
        for args in ({"kind": "liked"}, {"kind": "my-playlist", "query": "corrida"}):
            with self.assertRaisesRegex(Refused, "^" + NEEDS_ACCOUNT + "$"):
                self.music.play(**args)
        for kind in ("playlists", "liked", "albums", "history"):
            with self.assertRaisesRegex(Refused, NEEDS_ACCOUNT):
                self.music.library(kind)

    def test_my_playlist_by_its_title_without_case_or_accents(self):
        self.music.play(query="CORRIDA matinal", kind="my-playlist", auth=AUTH)
        self.assertEqual(("get_playlist", "PLrun", 100), self.clients.signed_in.calls[-1])
        self.assertEqual(100, len(self.queue))
        self.music.play(query="para dormir", kind="my-playlist", auth=AUTH)
        self.assertEqual(("get_playlist", "PLsleep", 100), self.clients.signed_in.calls[-1])
        with self.assertRaisesRegex(Refused, "none of your playlists"):
            self.music.play(query="heavy metal workout", kind="my-playlist", auth=AUTH)

    def test_liked(self):
        self.music.play(kind="liked", auth=AUTH, shuffle=True)
        self.assertEqual(("get_liked_songs", 100), self.clients.signed_in.calls[-1])
        self.assertEqual(100, len(self.queue))

    def test_the_token_as_ytmusicapi_takes_it(self):
        self.music.play(kind="liked", auth=AUTH | {"extra": "ignored"})
        given, client_id, client_secret = self.clients.tokens[0]
        self.assertEqual(
            {
                "scope": "https://www.googleapis.com/auth/youtube",
                "token_type": "Bearer",
                "access_token": "ya29.access",
                "refresh_token": "1//refresh",
                "expires_at": 1_900_000_000,
                "expires_in": 100_000_000,
            },
            given,
        )
        self.assertEqual(("123.apps.googleusercontent.com", "GOCSPX-secret"), (client_id, client_secret))

    def test_library(self):
        self.assertEqual(
            [
                {"kind": "playlist", "id": "PLrun", "title": "Corrida Matinal", "count": 12},
                {"kind": "playlist", "id": "PLsleep", "title": "Para dormir", "count": 30},
            ],
            self.music.library("playlists", AUTH)["items"],
        )
        liked = self.music.library("liked", AUTH)["items"]
        self.assertEqual(25, len(liked))
        self.assertEqual(
            {"kind": "song", "id": "liked000000", "title": "Liked 0", "artist": "Artist"}, liked[0]
        )
        self.assertEqual(
            [{"kind": "album", "id": "MPREmine", "title": "Mine", "artist": "Band"}],
            self.music.library("albums", AUTH)["items"],
        )
        self.assertEqual("hist0000001", self.music.library("history", AUTH)["items"][0]["id"])
        with self.assertRaises(Refused):
            self.music.library("podcasts", AUTH)


class DataApiTest(ProviderTest):
    """YouTube Music refusing the account's token, as it does now."""

    def setUp(self):
        super().setUp()
        self.clients = FakeClients(signed_in=FakeYTMusic(refuse=True))
        self.music = self.provider(self.clients)

    def test_playlists_and_liked_through_the_data_api(self):
        self.assertEqual(
            ["PLrunD", "PLsleepD"], [p["id"] for p in self.music.library("playlists", AUTH)["items"]]
        )
        self.music.play(query="corrida", kind="my-playlist", auth=AUTH)
        self.assertEqual(("playlist_tracks", "PLrunD", 100), self.google.calls[-1])
        self.assertEqual(watch("d0000000000"), self.queue[0]["url"])
        self.music.play(kind="liked", auth=AUTH)
        self.assertEqual(("liked", 100), self.google.calls[-1])
        self.assertEqual(25, len(self.music.library("liked", AUTH)["items"]))

    def test_youtube_music_is_not_asked_again_for_a_while(self):
        self.music.library("playlists", AUTH)
        self.music.library("liked", AUTH)
        self.assertEqual(1, len(self.clients.signed_in.calls))
        self.now += ACCOUNT_RETRY_S
        self.music.library("liked", AUTH)
        self.assertEqual(2, len(self.clients.signed_in.calls))

    def test_albums_and_history_only_from_youtube_music(self):
        with self.assertRaisesRegex(Refused, "doesn't let grog see your saved albums now"):
            self.music.library("albums", AUTH)
        with self.assertRaisesRegex(Refused, "doesn't let grog see your history now"):
            self.music.library("history", AUTH)

    def test_logs_nothing_of_what_was_asked(self):
        with self.assertLogs("grog_music") as logs:
            self.music.library("playlists", AUTH)
            with self.assertRaises(Refused):
                self.music.play(query=SECRET_QUERY, kind="my-playlist", auth=AUTH)
            try:
                self.provider(FakeClients(anonymous=FakeYTMusic(refuse=True))).play(query=SECRET_QUERY)
            except Refused:
                pass
        text = "\n".join(logs.output)
        for secret in (SECRET_QUERY, SECRET_TITLE, SECRET_ARTIST, "ya29", "refresh"):
            self.assertNotIn(secret, text)
        self.assertIn("YTMusicServerError", text)


class HelpersTest(unittest.TestCase):
    def test_normalized(self):
        self.assertEqual("musica pra relaxar", normalized("Música  pra RELAXAR!"))

    def test_best_match(self):
        mine = [{"title": "Rock"}, {"title": "Rock Nacional"}, {"title": "Músicas para Relaxar"}]
        self.assertEqual("Rock", best_match("rock", mine)["title"])
        self.assertEqual("Rock Nacional", best_match("rock nacional", mine)["title"])
        self.assertEqual("Músicas para Relaxar", best_match("musicas pra relaxar", mine)["title"])
        self.assertEqual("Músicas para Relaxar", best_match("relaxar", mine)["title"])
        self.assertIsNone(best_match("jazz", mine))
        self.assertIsNone(best_match("x", []))

    def test_kind_of_id(self):
        self.assertEqual("album", kind_of_id("MPREb_x"))
        self.assertEqual("artist", kind_of_id("UCx"))
        self.assertEqual("playlist", kind_of_id("VLPLx"))
        self.assertEqual("playlist", kind_of_id("OLAK5uy_x"))
        self.assertEqual("song", kind_of_id("dQw4w9WgXcQ"))
        with self.assertRaises(Refused):
            kind_of_id("what")

    def test_account_and_token(self):
        self.assertIsNone(account(None, required=False))
        checked = account(AUTH | {"scope": None, "tokenType": None}, required=True)
        self.assertEqual("Bearer", checked["tokenType"])
        self.assertEqual(0, token(checked | {"expiresAt": 1000}, now=5000)["expires_in"])
        for bad in (
            "x",
            AUTH | {"expiresAt": "soon"},
            AUTH | {"clientSecret": ""},
            AUTH | {"expiresAt": True},
        ):
            with self.assertRaises(Refused):
                account(bad, required=True)


if __name__ == "__main__":
    unittest.main()
