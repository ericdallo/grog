"""The YouTube Music provider: searching it and resolving what to play with
ytmusicapi (anonymous: YouTube Music's endpoints refuse OAuth tokens now),
and the user's library with their Google token, through ytmusicapi when
YouTube Music takes it, else YouTube's Data API (google.py). What it finds
plays through the player, as watch URLs yt-dlp resolves. ytmusicapi loads
lazily, so the rest runs (and is tested) without it."""

import difflib
import logging
import re
import threading
import time
import unicodedata

from . import Refused
from .google import Google, smallest_artwork

log = logging.getLogger("grog_music")

WATCH = "https://music.youtube.com/watch?v={}"
MAX_RESULTS, MAX_LIBRARY = 10, 25
NOTHING = "nothing found for that"
NEEDS_ACCOUNT = "that needs your YouTube Music account: connect it"
SEARCH_KINDS = ("top", "songs", "albums", "playlists", "artists")
PLAY_KINDS = ("song", "video", "album", "playlist", "artist", "my-playlist", "liked")
LIBRARY_KINDS = ("playlists", "liked", "albums", "history")
# ytmusicapi's search filters, by kind.
FILTERS = {
    "song": "songs",
    "video": "videos",
    "album": "albums",
    "playlist": "playlists",
    "artist": "artists",
}
# How long YouTube Music isn't asked with the user's token again after it
# refused it.
ACCOUNT_RETRY_S = 6 * 3600
# How alike a playlist's title and what was asked must be.
MATCH_AT_LEAST = 0.6


def ytmusic(language, location, token=None, client_id=None, client_secret=None):
    """A ytmusicapi client: anonymous, or with `token` (ytmusicapi's OAuth
    token dict), which it refreshes by itself, in memory: given as a dict,
    not a file, it writes nothing."""
    from ytmusicapi import OAuthCredentials, YTMusic

    if token is None:
        return YTMusic(language=language, location=location)
    credentials = OAuthCredentials(client_id=client_id, client_secret=client_secret)
    return YTMusic(auth=token, oauth_credentials=credentials, language=language, location=location)


def account(auth, required):
    """The `auth` grog sent, checked: {accessToken, refreshToken, expiresAt
    (epoch ms), clientId, clientSecret, scope, tokenType}; None without one,
    unless `required`."""
    if auth is None:
        if required:
            raise Refused(NEEDS_ACCOUNT)
        return None
    strings = ("accessToken", "refreshToken", "clientId", "clientSecret")
    if (
        not isinstance(auth, dict)
        or not all(isinstance(auth.get(k), str) and auth[k] for k in strings)
        or not isinstance(auth.get("expiresAt"), (int, float))
        or isinstance(auth.get("expiresAt"), bool)
    ):
        raise Refused(
            "auth needs accessToken, refreshToken, expiresAt (epoch milliseconds), clientId and clientSecret"
        )
    return {k: auth[k] for k in strings} | {
        "expiresAt": int(auth["expiresAt"]),
        "scope": auth.get("scope")
        if isinstance(auth.get("scope"), str)
        else "https://www.googleapis.com/auth/youtube",
        "tokenType": auth.get("tokenType") if isinstance(auth.get("tokenType"), str) else "Bearer",
    }


def token(auth, now):
    """ytmusicapi's OAuth token dict for `auth`: exactly its six keys (it
    takes no others), expires_at in seconds."""
    expires_at = auth["expiresAt"] // 1000
    return {
        "scope": auth["scope"],
        "token_type": auth["tokenType"],
        "access_token": auth["accessToken"],
        "refresh_token": auth["refreshToken"],
        "expires_at": expires_at,
        "expires_in": max(0, int(expires_at - now)),
    }


def normalized(text):
    """`text` without case, accents or punctuation."""
    text = unicodedata.normalize("NFKD", text.casefold())
    return " ".join(re.findall(r"\w+", "".join(c for c in text if not unicodedata.combining(c))))


def best_match(query, entries):
    """The entry whose title is most like `query` (case- and
    accent-insensitive), or None when none is alike enough."""
    q = normalized(query)

    def score(entry):
        t = normalized(entry["title"])
        if t == q:
            return 3.0
        ratio = difflib.SequenceMatcher(None, q, t).ratio()
        contained = q and t and (f" {q} " in f" {t} " or f" {t} " in f" {q} ")
        return 1.0 + ratio if contained else ratio

    scored = [(score(e), i) for i, e in enumerate(entries)]
    best = max(scored, default=None)
    return entries[best[1]] if best is not None and best[0] >= MATCH_AT_LEAST else None


def _seconds(text):
    """Seconds of "3:45" or "1:02:03", or None."""
    if not isinstance(text, str) or not re.fullmatch(r"\d+(:\d{1,2}){1,2}", text):
        return None
    total = 0
    for part in text.split(":"):
        total = total * 60 + int(part)
    return total


def _name(v):
    if isinstance(v, str):
        return v or None
    if isinstance(v, dict):
        return _name(v.get("name"))
    if isinstance(v, list):
        names = [n for n in (_name(x) for x in v) if n]
        return ", ".join(names) or None
    return None


def _entry(kind, id, title, **more):
    """{kind, id, title, ...more} without what isn't known, or None without an
    id or a title."""
    if not isinstance(id, str) or not id or not isinstance(title, str) or not title:
        return None
    return {"kind": kind, "id": id, "title": title} | {k: v for k, v in more.items() if v not in (None, "")}


def track(t, album=None, artist=None, artwork=None):
    """A song of ytmusicapi's (a playlist's, an album's, up next...), its
    artwork its own (up next's are in thumbnail), else `artwork`."""
    if not isinstance(t, dict):
        return None
    duration = t.get("duration_seconds")
    return _entry(
        "song",
        t.get("videoId"),
        t.get("title"),
        artist=_name(t.get("artists")) or _name(t.get("artist")) or artist,
        album=_name(t.get("album")) or album,
        durationS=duration if isinstance(duration, int) else _seconds(t.get("duration") or t.get("length")),
        artwork=smallest_artwork(t.get("thumbnails") or t.get("thumbnail")) or artwork,
    )


def result(r):
    """A search result of ytmusicapi's, as {kind, id, title, artist?, album?,
    durationS?}, or None for kinds it doesn't play (podcasts, episodes...)."""
    if not isinstance(r, dict):
        return None
    kind = r.get("resultType")
    if kind in ("song", "video"):
        entry = track(r)
        return entry and entry | {"kind": kind}
    if kind == "album":
        return _entry("album", r.get("browseId"), r.get("title"), artist=_name(r.get("artists")))
    if kind == "playlist":
        return _entry(
            "playlist",
            r.get("browseId") or r.get("playlistId"),
            r.get("title"),
            artist=_name(r.get("author")) or _name(r.get("artists")),
        )
    if kind == "artist":
        # The top result's card has its id and name in artists.
        first = (r.get("artists") or [None])[0]
        first = first if isinstance(first, dict) else {}
        return _entry(
            "artist", r.get("browseId") or first.get("id"), _name(r.get("artist")) or _name(first.get("name"))
        )
    return None


def kind_of_id(id):
    """What a YouTube Music id is, by its shape."""
    if id.startswith("MPRE"):
        return "album"
    if id.startswith("UC"):
        return "artist"
    if id.startswith(("VL", "PL", "RD", "OLAK", "LM", "LL")):
        return "playlist"
    if re.fullmatch(r"[\w-]{11}", id):
        return "song"
    raise Refused("that isn't a YouTube Music id")


def item(entry):
    """What the player plays for a song."""
    return {"url": WATCH.format(entry["id"])} | {
        k: entry[k] for k in ("title", "artist", "album", "artwork") if k in entry
    }


class YouTubeMusic:
    """The provider, playing through `player`, its queues at most
    `max_queue` long. `client(token=None, client_id=None,
    client_secret=None)` makes ytmusicapi clients; `google`, a Google."""

    def __init__(self, player, client, google=None, max_queue=100, clock=time.monotonic, wall=time.time):
        self.player, self.client, self.max_queue = player, client, max_queue
        self.google = google if google is not None else Google()
        self.clock, self.wall = clock, wall
        self._anonymous = None
        self._lock = threading.Lock()
        self._account_refused_until = None

    def anonymous(self):
        with self._lock:
            if self._anonymous is None:
                self._anonymous = self.client()
            return self._anonymous

    def _ask(self, call):
        """`call(client)` with the anonymous client."""
        try:
            return call(self.anonymous())
        except Refused:
            raise
        except Exception as e:
            log.warning("YouTube Music failed (%s)", type(e).__name__)
            raise Refused("YouTube Music didn't answer that") from e

    def _with_account(self, auth, call, fallback=None, what="that"):
        """`call(client)` with a client signed in with `auth`, while YouTube
        Music takes the user's token; when it refuses it (HTTP 400), or
        refused it lately, `fallback()`, or Refused without one."""
        if self._account_refused_until is None or self.clock() >= self._account_refused_until:
            try:
                signed_in = self.client(
                    token=token(auth, self.wall()),
                    client_id=auth["clientId"],
                    client_secret=auth["clientSecret"],
                )
                return call(signed_in)
            except Refused:
                raise
            except Exception as e:
                if "400" not in str(e):
                    log.warning("YouTube Music failed with the account (%s)", type(e).__name__)
                    raise Refused("YouTube Music didn't answer that") from e
                log.warning(
                    "YouTube Music refused the account's token (%s): not asking it again for a while",
                    type(e).__name__,
                )
                self._account_refused_until = self.clock() + ACCOUNT_RETRY_S
        if fallback is None:
            raise Refused(f"YouTube Music doesn't let grog see your {what} now")
        return fallback()

    def search(self, query, kind="top"):
        """{results: [{kind, id, title, artist?, album?, durationS?}]}, at most
        MAX_RESULTS."""
        if not isinstance(query, str) or not query.strip():
            raise Refused("say what to search for")
        if kind not in SEARCH_KINDS:
            raise Refused(f"kind must be one of {', '.join(SEARCH_KINDS)}")
        found = self._ask(lambda c: c.search(query, filter=FILTERS.get(kind.rstrip("s"))))
        results = [
            {k: v for k, v in r.items() if k != "artwork"} for r in (result(x) for x in found or []) if r
        ]
        return {"results": results[:MAX_RESULTS]}

    def _find(self, query, kind):
        """(kind, id) of the first search result for `query` of `kind`, any
        playable one for None."""
        found = self._ask(lambda c: c.search(query, filter=FILTERS.get(kind)))
        for r in (result(x) for x in found or []):
            if r and (kind is None or r["kind"] == kind):
                return r["kind"], r["id"]
        raise Refused(NOTHING)

    def _tracks(self, kind, id):
        """The songs to play for a YouTube Music thing: a song then its up
        next (like the app), an album's or playlist's tracks, an artist's top
        songs."""
        limit = self.max_queue
        if kind in ("song", "video"):
            watch = self._ask(lambda c: c.get_watch_playlist(videoId=id, limit=limit))
            songs = [t for t in (track(x) for x in (watch or {}).get("tracks") or []) if t]
            if not songs or songs[0]["id"] != id:
                songs = [{"kind": "song", "id": id, "title": ""}] + [t for t in songs if t["id"] != id]
            return songs
        if kind == "album":
            album = self._ask(lambda c: c.get_album(id)) or {}
            title, artist = _name(album.get("title")), _name(album.get("artists"))
            artwork = smallest_artwork(album.get("thumbnails"))
            tracks = (
                track(x, album=title, artist=artist, artwork=artwork) for x in album.get("tracks") or []
            )
            return [t for t in tracks if t]
        if kind == "playlist":
            playlist = self._ask(lambda c: c.get_playlist(id, limit=limit)) or {}
            return [t for t in (track(x) for x in playlist.get("tracks") or []) if t]
        if kind == "artist":
            songs = (self._ask(lambda c: c.get_artist(id)) or {}).get("songs") or {}
            if isinstance(songs.get("browseId"), str):
                playlist = self._ask(lambda c: c.get_playlist(songs["browseId"], limit=limit)) or {}
                tracks = playlist.get("tracks") or []
            else:
                tracks = songs.get("results") or []
            return [t for t in (track(x) for x in tracks) if t]
        raise Refused(f"kind must be one of {', '.join(PLAY_KINDS)}")

    def _my_playlists(self, auth):
        return self._with_account(
            auth,
            lambda c: [
                p
                for p in (
                    _entry("playlist", x.get("playlistId"), x.get("title"), count=_count(x.get("count")))
                    for x in c.get_library_playlists(limit=None)
                    if isinstance(x, dict)
                )
                if p and p["id"] != "LM"
            ],
            lambda: self.google.playlists(auth),
        )

    def _my_playlist_tracks(self, auth, playlist_id):
        return self._with_account(
            auth,
            lambda c: [
                t
                for t in (
                    track(x) for x in c.get_playlist(playlist_id, limit=self.max_queue).get("tracks") or []
                )
                if t
            ],
            lambda: self.google.playlist_tracks(auth, playlist_id, self.max_queue),
        )

    def _liked(self, auth, limit):
        return self._with_account(
            auth,
            lambda c: [
                t for t in (track(x) for x in c.get_liked_songs(limit=limit).get("tracks") or []) if t
            ],
            lambda: self.google.liked(auth, limit),
        )

    def play(self, query=None, kind=None, id=None, shuffle=False, auth=None):
        """Finds what to play and plays it: {playing {title, artist}, count,
        ...the player's status}."""
        if kind is not None and kind not in PLAY_KINDS + SEARCH_KINDS:
            raise Refused(f"kind must be one of {', '.join(PLAY_KINDS)}")
        kind = None if kind in (None, "top") else kind.removesuffix("s") if kind in SEARCH_KINDS else kind
        query = query.strip() if isinstance(query, str) and query.strip() else None
        if id is not None and (not isinstance(id, str) or not id.strip()):
            raise Refused("id must be a YouTube Music id")
        if kind == "liked":
            songs = self._liked(account(auth, required=True), self.max_queue)
        elif kind == "my-playlist":
            auth = account(auth, required=True)
            if id is None and query is None:
                raise Refused("say which of your playlists")
            if id is None:
                mine = best_match(query, self._my_playlists(auth))
                if mine is None:
                    raise Refused("none of your playlists is called that")
                id = mine["id"]
            songs = self._my_playlist_tracks(auth, id)
        else:
            account(auth, required=False)
            if id is not None:
                kind, target = kind or kind_of_id(id), id
            elif query is not None:
                kind, target = self._find(query, kind)
            else:
                raise Refused("say what to play: a query or an id")
            songs = self._tracks(kind, target)
        if not songs:
            raise Refused(NOTHING)
        status = self.player.play([item(s) for s in songs[: self.max_queue]], shuffle=bool(shuffle))
        playing = {k: status[k] for k in ("title", "artist") if k in status}
        return {"playing": playing} | status

    def library(self, kind=None, auth=None):
        """{items: [{kind, id, title, artist?, count?}]}, at most MAX_LIBRARY."""
        if kind not in LIBRARY_KINDS:
            raise Refused(f"kind must be one of {', '.join(LIBRARY_KINDS)}")
        auth = account(auth, required=True)
        if kind == "playlists":
            items = self._my_playlists(auth)
        elif kind == "liked":
            items = self._liked(auth, MAX_LIBRARY)
        elif kind == "albums":
            items = self._with_account(
                auth,
                lambda c: [
                    _entry("album", x.get("browseId"), x.get("title"), artist=_name(x.get("artists")))
                    for x in c.get_library_albums(limit=MAX_LIBRARY)
                    if isinstance(x, dict)
                ],
                what="saved albums",
            )
        else:
            items = self._with_account(auth, lambda c: [track(x) for x in c.get_history()], what="history")
        keep = ("kind", "id", "title", "artist", "count")
        return {"items": [{k: e[k] for k in keep if k in e} for e in items if e][:MAX_LIBRARY]}


def _count(v):
    """A playlist's song count: an int, or a string with one ("12 songs")."""
    if isinstance(v, int) and not isinstance(v, bool):
        return v
    digits = re.sub(r"\D", "", v) if isinstance(v, str) else ""
    return int(digits) if digits else None
