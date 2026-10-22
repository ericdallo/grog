"""Fakes of mpv (its JSON IPC, on a real unix socket), of ytmusicapi's
clients, of the player and of Google, for the tests."""

import json
import logging
import os
import shutil
import socket
import tempfile
import threading

# What grog-music logs stays out of the tests' output (assertLogs still sees
# it).
logging.getLogger("grog_music").addHandler(logging.NullHandler())

# What the tests play and ask for: none of it may reach the logs.
SECRET_QUERY, SECRET_TITLE, SECRET_ARTIST = "zanzibar lullaby", "Zanzibar Lullaby", "The Quiet Ones"


def temp_dir(test):
    """A short temporary directory (unix socket paths are short), removed
    after `test`."""
    path = tempfile.mkdtemp(prefix="gm-")
    test.addCleanup(shutil.rmtree, path, ignore_errors=True)
    return path


class FakeMpv:
    """mpv's IPC at `path`, answering like mpv: its playlist in .playlist
    (URLs), .pos, its properties in .props; what it was asked in .commands.
    Also a Popen: poll, kill, wait. die() makes it exit."""

    def __init__(self, path):
        self.path = path
        self.commands = []
        self.playlist, self.pos = [], -1
        self.props = {"pause": False, "volume": 100}
        self.returncode = None
        self._conns = []
        self._server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self._server.bind(path)
        self._server.listen(4)
        threading.Thread(target=self._accept, daemon=True).start()

    def asked(self, name):
        """The commands named `name` it was sent."""
        return [c for c in self.commands if c[0] == name]

    def _accept(self):
        while True:
            try:
                conn, _ = self._server.accept()
            except OSError:
                return
            self._conns.append(conn)
            threading.Thread(target=self._serve, args=(conn,), daemon=True).start()

    def _serve(self, conn):
        try:
            for line in conn.makefile("rb"):
                request = json.loads(line)
                command = request["command"]
                self.commands.append(command)
                error, data = self._run(command)
                # Events come in between, as with mpv.
                conn.sendall(b'{"event":"property-change","name":"volume"}\n')
                reply = {"request_id": request["request_id"], "error": error}
                if data is not None:
                    reply["data"] = data
                conn.sendall(json.dumps(reply).encode() + b"\n")
                if command[0] == "quit":
                    self.die()
        except OSError:
            pass

    def _run(self, command):
        name, args = command[0], command[1:]
        if name == "get_property":
            value = {"playlist-pos": self.pos, "playlist-count": len(self.playlist)}.get(
                args[0], self.props.get(args[0])
            )
            return ("property unavailable", None) if value is None else ("success", value)
        if name == "set_property":
            self.props[args[0]] = args[1]
        elif name == "loadfile":
            url, mode = args
            if mode == "replace":
                self.playlist, self.pos = [url], 0
            else:
                self.playlist.append(url)
                if mode == "append-play" and self.pos < 0:
                    self.pos = len(self.playlist) - 1
        elif name == "stop":
            self.playlist, self.pos = [], -1
        elif name == "playlist-next":
            if self.pos + 1 >= len(self.playlist):
                return "error running command", None
            self.pos += 1
        elif name == "playlist-prev":
            if self.pos <= 0:
                return "error running command", None
            self.pos -= 1
        elif name != "quit":
            return "invalid parameter", None
        return "success", None

    def die(self):
        self.returncode = -9
        self._server.close()
        for conn in self._conns:
            try:
                conn.shutdown(socket.SHUT_RDWR)
                conn.close()
            except OSError:
                pass

    def poll(self):
        return self.returncode

    def kill(self):
        self.die()

    def wait(self, timeout=None):
        return self.returncode


class FakeSpawn:
    """subprocess.Popen for mpv: a FakeMpv at the socket its arguments say,
    each in .started, their arguments in .argv. With `fail`, raises it."""

    def __init__(self, fail=None):
        self.fail = fail
        self.started, self.argv = [], []

    def __call__(self, argv, **_):
        if self.fail is not None:
            raise self.fail
        self.argv.append(argv)
        path = next(a.split("=", 1)[1] for a in argv if a.startswith("--input-ipc-server="))
        if os.path.exists(path):
            raise AssertionError("the socket of an earlier mpv is still there")
        self.started.append(FakeMpv(path))
        return self.started[-1]

    @property
    def mpv(self):
        return self.started[-1]


class YTMusicServerError(Exception):
    """Like ytmusicapi's."""


def song(video_id, title, artist="Artist", album="Album", seconds=200):
    return {
        "videoId": video_id,
        "title": title,
        "artists": [{"name": artist, "id": "UCartist"}],
        "album": {"name": album, "id": "MPREalbum"},
        "duration_seconds": seconds,
    }


SONG_ID, ALBUM_ID, PLAYLIST_ID, ARTIST_ID = "aaaaaaaaaaa", "MPREb_album", "VLPLpublic", "UCartist00"

SEARCH = {
    None: [
        {"category": "Podcasts", "resultType": "podcast", "title": "A podcast", "browseId": "MPSPx"},
        # As ytmusicapi 1.12.3 gives the top result's card.
        {
            "category": "Top result",
            "resultType": "artist",
            "artist": None,
            "artists": [{"name": SECRET_ARTIST, "id": ARTIST_ID}],
            "browseId": None,
        },
        song(SONG_ID, SECRET_TITLE, SECRET_ARTIST) | {"resultType": "song", "category": "Songs"},
    ],
    "songs": [song(SONG_ID, SECRET_TITLE, SECRET_ARTIST) | {"resultType": "song"}]
    + [song(f"song{i:07d}", f"Song {i}") | {"resultType": "song"} for i in range(15)],
    "albums": [
        {
            "resultType": "album",
            "title": "Calm",
            "type": "Album",
            "artists": [{"name": SECRET_ARTIST, "id": ARTIST_ID}],
            "browseId": ALBUM_ID,
        }
    ],
    "playlists": [
        {"resultType": "playlist", "title": "Calm mix", "author": "Someone", "browseId": PLAYLIST_ID}
    ],
    "artists": [{"resultType": "artist", "artist": SECRET_ARTIST, "browseId": ARTIST_ID}],
    "videos": [song("vvvvvvvvvvv", "A video") | {"resultType": "video"}],
}


class FakeYTMusic:
    """A ytmusicapi client with canned answers, what it was asked in .calls.
    With `refuse`, every call fails as YouTube Music does with OAuth tokens
    now."""

    def __init__(self, refuse=False):
        self.refuse = refuse
        self.calls = []

    def _call(self, *call):
        self.calls.append(call)
        if self.refuse:
            raise YTMusicServerError(
                "Server returned HTTP 400: Bad Request.\nRequest contains an invalid argument."
            )

    def search(self, query, filter=None):
        self._call("search", query, filter)
        return SEARCH.get(filter, [])

    def get_watch_playlist(self, videoId, limit=25):
        self._call("get_watch_playlist", videoId, limit)
        return {
            "tracks": [
                {
                    "videoId": videoId,
                    "title": SECRET_TITLE,
                    "artists": [{"name": SECRET_ARTIST}],
                    "length": "3:45",
                },
                {
                    "videoId": "next0000001",
                    "title": "Up next 1",
                    "artists": [{"name": "B"}],
                    "length": "4:00",
                },
                {
                    "videoId": "next0000002",
                    "title": "Up next 2",
                    "artists": [{"name": "C"}],
                    "length": "1:02:03",
                },
            ]
        }

    def get_album(self, browseId):
        self._call("get_album", browseId)
        return {
            "title": "Calm",
            "artists": [{"name": SECRET_ARTIST}],
            "tracks": [
                {"videoId": f"album{i:06d}", "title": f"Track {i}", "artists": None, "album": None}
                for i in range(1, 4)
            ]
            + [{"videoId": None, "title": "Not playable"}],
        }

    def get_playlist(self, playlistId, limit=100):
        self._call("get_playlist", playlistId, limit)
        return {
            "title": "A playlist",
            "tracks": [song(f"pl{i:09d}", f"Playlist song {i}") for i in range(150)],
        }

    def get_artist(self, channelId):
        self._call("get_artist", channelId)
        return {
            "name": SECRET_ARTIST,
            "songs": {"browseId": "VLOLAKtopsongs", "results": [song("top00000001", "Top 1")]},
        }

    def get_library_playlists(self, limit=25):
        self._call("get_library_playlists", limit)
        return [
            {"playlistId": "LM", "title": "Liked Music"},
            {"playlistId": "PLrun", "title": "Corrida Matinal", "count": "12 songs"},
            {"playlistId": "PLsleep", "title": "Para dormir", "count": 30},
        ]

    def get_liked_songs(self, limit=100):
        self._call("get_liked_songs", limit)
        return {"tracks": [song(f"liked{i:06d}", f"Liked {i}") for i in range(limit + 5)]}

    def get_library_albums(self, limit=25):
        self._call("get_library_albums", limit)
        return [{"browseId": "MPREmine", "title": "Mine", "artists": [{"name": "Band"}]}]

    def get_history(self):
        self._call("get_history")
        return [song("hist0000001", "Heard before")]


class FakeClients:
    """ytmusicapi clients: `anonymous` without a token, `signed_in` with one;
    the tokens it was given in .tokens."""

    def __init__(self, anonymous=None, signed_in=None):
        self.anonymous = anonymous or FakeYTMusic()
        self.signed_in = signed_in or FakeYTMusic()
        self.made, self.tokens = 0, []

    def __call__(self, token=None, client_id=None, client_secret=None):
        self.made += 1
        if token is None:
            return self.anonymous
        self.tokens.append((token, client_id, client_secret))
        return self.signed_in


class FakePlayer:
    """What it was told to play, in .played: (items, shuffle)."""

    def __init__(self):
        self.played = []

    def play(self, items, shuffle=False):
        self.played.append((items, shuffle))
        first = items[0]
        return {"state": "playing", "volume": 100, "ducked": False, "count": len(items), "index": 0} | {
            k: first[k] for k in ("title", "artist", "album") if k in first
        }


class FakeGoogle:
    """YouTube's Data API, canned, what it was asked in .calls."""

    def __init__(self):
        self.calls = []

    def playlists(self, auth):
        self.calls.append(("playlists",))
        return [
            {"kind": "playlist", "id": "PLrunD", "title": "Corrida matinal", "count": 12},
            {"kind": "playlist", "id": "PLsleepD", "title": "Para Dormir", "count": 30},
        ]

    def playlist_tracks(self, auth, playlist_id, limit):
        self.calls.append(("playlist_tracks", playlist_id, limit))
        return [
            {"kind": "song", "id": f"d{i:010d}", "title": f"Data song {i}", "artist": "X"} for i in range(3)
        ]

    def liked(self, auth, limit):
        self.calls.append(("liked", limit))
        return [{"kind": "song", "id": f"l{i:010d}", "title": f"Liked {i}"} for i in range(min(limit, 40))]


AUTH = {
    "accessToken": "ya29.access",
    "refreshToken": "1//refresh",
    "expiresAt": 1_900_000_000_000,
    "clientId": "123.apps.googleusercontent.com",
    "clientSecret": "GOCSPX-secret",
    "scope": "https://www.googleapis.com/auth/youtube",
    "tokenType": "Bearer",
}
