import json
import threading
import unittest
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from fakes import AUTH

from grog_music import Refused
from grog_music.google import MAX_PAGES, AccountError, Google, artist_of_channel, iso_seconds


class FakeGoogleServer:
    """YouTube's Data API and Google's token endpoint on 127.0.0.1: answers
    from .routes ({path: (status, body) or a function of the query}), what it
    was asked in .requests (path, query, headers, body)."""

    def __init__(self, test):
        self.routes, self.requests = {}, []
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def _answer(self):
                url = urllib.parse.urlsplit(self.path)
                query = dict(urllib.parse.parse_qsl(url.query))
                length = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(length).decode() if length else ""
                fake.requests.append((url.path, query, dict(self.headers), body))
                route = fake.routes.get(url.path, (404, {}))
                status, answer = route(query) if callable(route) else route
                data = json.dumps(answer).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            do_GET = do_POST = _answer

            def log_message(self, *_):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        test.addCleanup(self.server.server_close)
        test.addCleanup(self.server.shutdown)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"


def playlist_page(query):
    if query.get("pageToken") == "p2":
        return 200, {
            "items": [{"id": "PL3", "snippet": {"title": "Three"}, "contentDetails": {"itemCount": 0}}]
        }
    return 200, {
        "nextPageToken": "p2",
        "items": [
            {"id": "PL1", "snippet": {"title": "Corrida"}, "contentDetails": {"itemCount": 12}},
            {"id": "PL2", "snippet": {"title": "Dormir"}},
        ],
    }


class GoogleTest(unittest.TestCase):
    def setUp(self):
        self.fake = FakeGoogleServer(self)
        self.now = 1_800_000_000.0
        self.google = Google(
            api_url=self.fake.url + "/youtube/v3", token_url=self.fake.url + "/token", clock=lambda: self.now
        )
        self.auth = dict(AUTH, expiresAt=int((self.now + 3600) * 1000))

    def test_playlists_all_pages_with_the_token(self):
        self.fake.routes["/youtube/v3/playlists"] = playlist_page
        self.assertEqual(
            [
                {"kind": "playlist", "id": "PL1", "title": "Corrida", "count": 12},
                {"kind": "playlist", "id": "PL2", "title": "Dormir"},
                {"kind": "playlist", "id": "PL3", "title": "Three", "count": 0},
            ],
            self.google.playlists(self.auth),
        )
        path, query, headers, _ = self.fake.requests[0]
        self.assertEqual({"part": "snippet,contentDetails", "mine": "true", "maxResults": "50"}, query)
        self.assertEqual("Bearer ya29.access", headers["Authorization"])
        self.assertEqual("p2", self.fake.requests[1][1]["pageToken"])

    def test_playlist_tracks_without_deleted_ones(self):
        self.fake.routes["/youtube/v3/playlistItems"] = (
            200,
            {
                "items": [
                    {
                        "snippet": {"title": "Song", "videoOwnerChannelTitle": "Band - Topic"},
                        "contentDetails": {"videoId": "v1"},
                    },
                    {"snippet": {"title": "Deleted video"}, "contentDetails": {"videoId": "v2"}},
                ]
            },
        )
        self.assertEqual(
            [{"kind": "song", "id": "v1", "title": "Song", "artist": "Band"}],
            self.google.playlist_tracks(self.auth, "PL1", 100),
        )
        self.assertEqual("PL1", self.fake.requests[0][1]["playlistId"])

    def test_liked_music_only(self):
        self.fake.routes["/youtube/v3/videos"] = (
            200,
            {
                "items": [
                    {
                        "id": "v1",
                        "snippet": {"title": "Liked", "channelTitle": "Singer", "categoryId": "10"},
                        "contentDetails": {"duration": "PT3M20S"},
                    },
                    {"id": "v2", "snippet": {"title": "A podcast", "categoryId": "22"}},
                    {"id": "v3", "snippet": {"title": "No category", "channelTitle": "Someone"}},
                ]
            },
        )
        self.assertEqual(
            [{"kind": "song", "id": "v1", "title": "Liked", "artist": "Singer", "durationS": 200}],
            self.google.liked(self.auth, 100),
        )
        self.assertEqual("like", self.fake.requests[0][1]["myRating"])

    def test_liked_reads_on_for_enough_music(self):
        def page(query):
            n = int(query.get("pageToken") or 0)
            music = {"id": f"m{n}", "snippet": {"title": f"Song {n}", "categoryId": "10"}}
            other = {"id": f"o{n}", "snippet": {"title": f"Video {n}", "categoryId": "24"}}
            return 200, {"nextPageToken": str(n + 1), "items": [other, music]}

        self.fake.routes["/youtube/v3/videos"] = page
        self.assertEqual(["m0", "m1", "m2"], [s["id"] for s in self.google.liked(self.auth, 3)])
        self.assertEqual(3, len(self.fake.requests))
        self.fake.requests.clear()
        self.assertEqual(MAX_PAGES, len(self.google.liked(self.auth, 100)))
        self.assertEqual(MAX_PAGES, len(self.fake.requests))

    def test_refreshes_a_token_about_to_expire_in_memory(self):
        self.auth["expiresAt"] = int((self.now + 30) * 1000)
        self.fake.routes["/token"] = (200, {"access_token": "ya29.fresh", "expires_in": 3599})
        self.fake.routes["/youtube/v3/videos"] = (200, {"items": []})
        self.google.liked(self.auth, 10)
        path, _, _, body = self.fake.requests[0]
        self.assertEqual("/token", path)
        self.assertEqual(
            {
                "client_id": "123.apps.googleusercontent.com",
                "client_secret": "GOCSPX-secret",
                "refresh_token": "1//refresh",
                "grant_type": "refresh_token",
            },
            dict(urllib.parse.parse_qsl(body)),
        )
        self.assertEqual("Bearer ya29.fresh", self.fake.requests[1][2]["Authorization"])
        self.assertEqual(int((self.now + 3599) * 1000), self.auth["expiresAt"])

    def test_errors(self):
        self.fake.routes["/youtube/v3/videos"] = (401, {})
        with self.assertRaisesRegex(AccountError, "connecting again"):
            self.google.liked(self.auth, 10)
        self.fake.routes["/youtube/v3/videos"] = (403, {})
        with self.assertRaisesRegex(Refused, "YouTube Data API enabled"):
            self.google.liked(self.auth, 10)
        self.auth["expiresAt"] = 0
        self.fake.routes["/token"] = (400, {"error": "invalid_grant"})
        with self.assertRaisesRegex(AccountError, "connecting again"):
            self.google.liked(self.auth, 10)

    def test_unreachable(self):
        google = Google(api_url="http://127.0.0.1:1/youtube/v3", timeout_s=2, clock=lambda: self.now)
        with self.assertRaisesRegex(Refused, "YouTube didn't answer"):
            google.liked(self.auth, 10)

    def test_helpers(self):
        self.assertEqual("Band", artist_of_channel("Band - Topic"))
        self.assertEqual("Band", artist_of_channel("Band"))
        self.assertEqual(3723, iso_seconds("PT1H2M3S"))
        self.assertEqual(86400, iso_seconds("P1D"))
        self.assertIsNone(iso_seconds("P"))
        self.assertIsNone(iso_seconds(None))


if __name__ == "__main__":
    unittest.main()
