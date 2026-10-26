"""grog's player: a queue of playable items, from any provider, played by
mpv, with its volume and ducking (lower while grog listens or speaks, then
back)."""

import logging
import random
import threading
import time
from urllib.parse import urlsplit

from . import Refused
from .mpv import MpvError

log = logging.getLogger("grog_music")

# What YouTube's hosts serve goes straight to yt-dlp (mpv's ytdl:// prefix),
# rather than mpv first fetching the page, failing, then asking yt-dlp.
YOUTUBE_HOSTS = ("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")
MAX_URL, MAX_TEXT = 4096, 300
ITEMS = 'send items: a list of {"url": "..."}, with a title, artist, album and artwork when known'
# What status says when an entry couldn't play: safe to show and to log, as
# it says nothing of what it was.
YOUTUBE_FAILED = "Couldn't play that: it may be unavailable, or YouTube changed (yt-dlp)."
FAILED = "Couldn't play that: it may be unavailable, or in a format the player can't play."


def playable(url):
    """Whether `url` is something grog-music plays: http(s), or file:// (a
    local file, by its absolute path)."""
    if not isinstance(url, str) or len(url) > MAX_URL or any(ord(c) < 32 or ord(c) == 127 for c in url):
        return False
    try:
        parts = urlsplit(url)
    except ValueError:
        return False
    if parts.scheme in ("http", "https"):
        return bool(parts.hostname)
    return parts.scheme == "file" and parts.netloc in ("", "localhost") and parts.path.startswith("/")


def youtube(url):
    """Whether `url` is YouTube's, played through yt-dlp."""
    return (urlsplit(url).hostname or "").lower() in YOUTUBE_HOSTS


def mpv_url(url):
    """What mpv is told to load for `url`."""
    return "ytdl://" + url if youtube(url) else url


def artwork(url):
    """`url` when it's artwork the screen may show (https), else None."""
    return url if playable(url) and urlsplit(url).scheme == "https" else None


def failure(event, urls):
    """What to say when mpv's `event` is an entry failing to play (end-file,
    reason error), else None. `urls`: the URL each playlist entry id was
    loaded from, as far as known."""
    if not isinstance(event, dict) or event.get("event") != "end-file" or event.get("reason") != "error":
        return None
    url = urls.get(event.get("playlist_entry_id"))
    return YOUTUBE_FAILED if url is not None and youtube(url) else FAILED


def items_from(items, limit):
    """The first `limit` of `items`, checked, with only what the player keeps
    of each. Raises Refused when they aren't playable items."""
    if not isinstance(items, list) or not items:
        raise Refused(ITEMS)
    out = []
    for item in items[:limit]:
        if not isinstance(item, dict):
            raise Refused(ITEMS)
        if not playable(item.get("url")):
            raise Refused("each item needs a url: http://, https:// or file:// (an absolute path)")
        picture = artwork(item.get("artwork"))
        out.append(
            {"url": item["url"]}
            | {
                k: item[k].strip()[:MAX_TEXT]
                for k in ("title", "artist", "album")
                if isinstance(item.get(k), str) and item[k].strip()
            }
            | ({"artwork": picture} if picture else {})
        )
    return out


def _level(v, name):
    if not isinstance(v, int) or isinstance(v, bool) or not 0 <= v <= 100:
        raise Refused(f"{name} must be a whole number from 0 to 100")
    return v


def _seconds(v):
    return round(v, 1) if isinstance(v, (int, float)) and not isinstance(v, bool) and v >= 0 else None


class Player:
    """mpv (an Mpv, set as .mpv: call on_mpv_start each time it starts, and
    on_mpv_event with its events), its volume at `volume`, ducked to
    `duck_level` unless told another, holding at most `max_queue` items.
    `shuffle(items)` shuffles a list in place; `clock()` is the time, in
    seconds since the epoch."""

    def __init__(
        self, mpv=None, volume=100, duck_level=20, max_queue=100, shuffle=random.shuffle, clock=time.time
    ):
        self.mpv, self.level, self.duck_level, self.max_queue = mpv, volume, duck_level, max_queue
        self._shuffle, self._clock = shuffle, clock
        self.ducked_to = None
        self.items = []
        # The URL each of mpv's playlist entries was loaded from, by its id.
        self._urls = {}
        # How many times mpv started: a queue being loaded while it changes
        # was partly loaded into an mpv that's gone.
        self.generation = 0
        # (what to say, when in epoch ms) of the last entry that couldn't
        # play, until something plays.
        self.failed = None
        self._lock = threading.RLock()

    def on_mpv_start(self):
        """A new mpv has nothing queued, and its own volume. It runs within
        mpv's lock, maybe while another thread holds the player's, so it
        doesn't take it: what it changes is replaced whole."""
        self.generation += 1
        self.items, self._urls = [], {}
        self._apply_volume()

    def on_mpv_event(self, event):
        """Notes an entry that couldn't play, and forgets it once one plays.
        Like on_mpv_start, without the player's lock."""
        sentence = failure(event, self._urls)
        if sentence is not None:
            log.warning("mpv couldn't play an entry")
            self.failed = (sentence, int(self._clock() * 1000))
        elif event.get("event") in ("file-loaded", "playback-restart"):
            self.failed = None

    def _effective_volume(self):
        return self.level if self.ducked_to is None else min(self.level, self.ducked_to)

    def _apply_volume(self):
        self.mpv.set("volume", self._effective_volume())

    def status(self):
        """{state "playing"|"paused"|"idle", title, artist, album, positionS,
        durationS, volume, ducked, index (from 0), count, artwork, error,
        errorAt}, without the first ones when they aren't known; artwork
        (the current item's), error (why the last entry couldn't play) and
        errorAt (epoch ms) are null then."""
        with self._lock:
            position = self.mpv.get("playlist-pos")
            count = self.mpv.get("playlist-count") or 0
            error, error_at = self.failed or (None, None)
            out = {
                "volume": self.level,
                "ducked": self.ducked_to is not None,
                "count": count,
                "error": error,
                "errorAt": error_at,
            }
            if not isinstance(position, int) or position < 0:
                return {"state": "idle"} | out | {"artwork": None}
            out = {"state": "paused" if self.mpv.get("pause") else "playing"} | out | {"index": position}
            # mpv's playlist holds what the player loaded, unless something
            # (a playlist URL) became several entries.
            item = self.items[position] if count == len(self.items) and position < count else {}
            metadata = self.mpv.get("metadata")
            metadata = {str(k).lower(): v for k, v in metadata.items()} if isinstance(metadata, dict) else {}
            known = {
                "title": item.get("title")
                or metadata.get("title")
                or metadata.get("icy-title")
                or self.mpv.get("media-title"),
                "artist": item.get("artist") or metadata.get("artist"),
                "album": item.get("album") or metadata.get("album"),
                "positionS": _seconds(self.mpv.get("time-pos")),
                "durationS": _seconds(self.mpv.get("duration")),
            }
            known = {k: v for k, v in known.items() if isinstance(v, (str, float, int)) and v != ""}
            return out | known | {"artwork": item.get("artwork")}

    def _idle(self):
        position = self.mpv.get("playlist-pos")
        return not isinstance(position, int) or position < 0

    def _load(self, items, mode):
        """Loads `items` into mpv, keeping .items in step: with mode
        "replace", the first replaces its playlist and the rest follow; with
        "append-play", each goes at the end. False when mpv started again
        meanwhile: its playlist isn't what .items says then."""
        generation = self.generation
        for i, item in enumerate(items):
            how = "append" if mode == "replace" and i > 0 else mode
            reply = self.mpv.command("loadfile", mpv_url(item["url"]), how)
            if self.generation != generation:
                return False
            if how == "replace":
                # mpv's events from now on are about this queue.
                self.failed = None
            self.items.append(item)
            entry = reply.get("playlist_entry_id") if isinstance(reply, dict) else None
            if isinstance(entry, int):
                self._urls[entry] = item["url"]
        return True

    def _play(self, items):
        """Replaces the queue with `items`, checked, and plays them: loaded
        again from the start into a new mpv when it started again
        meanwhile."""
        for _ in range(2):
            self.items, self._urls = [], {}
            if self._load(items, "replace"):
                self.mpv.set("pause", False)
                return self.status()
            log.warning("mpv started again while the queue was loading: loading it again")
        raise MpvError("mpv kept starting again while the queue was loading")

    def play(self, items, shuffle=False):
        """Replaces the queue with `items` ({url, title?, artist?, album?,
        artwork?}), shuffled when asked, and plays them."""
        items = items_from(items, self.max_queue)
        if shuffle:
            self._shuffle(items)
        with self._lock:
            return self._play(items)

    def enqueue(self, items):
        """Adds `items` at the end of the queue, playing them when nothing
        plays."""
        items = items_from(items, self.max_queue)
        with self._lock:
            if self._idle():
                return self._play(items)
            room = self.max_queue - len(self.items)
            if room <= 0:
                raise Refused(f"the queue is full: it holds {self.max_queue}")
            if not self._load(items[:room], "append-play"):
                # A new mpv has nothing of the queue before: these are the
                # queue now.
                return self._play(items)
            return self.status()

    def pause(self):
        with self._lock:
            self.mpv.set("pause", True)
            return self.status()

    def resume(self):
        with self._lock:
            if self._idle():
                raise Refused("nothing is playing")
            self.mpv.set("pause", False)
            return self.status()

    def stop(self):
        """Stops, and clears the queue, and what couldn't play."""
        with self._lock:
            self.mpv.command("stop")
            self.items, self._urls, self.failed = [], {}, None
            return self.status()

    def _skip(self, command, refusal):
        with self._lock:
            if self._idle():
                raise Refused("nothing is playing")
            try:
                self.mpv.command(command, "weak")
            except MpvError as e:
                raise Refused(refusal) from e
            self.mpv.set("pause", False)
            return self.status()

    def next(self):
        return self._skip("playlist-next", "that's the last one in the queue")

    def previous(self):
        return self._skip("playlist-prev", "that's the first one in the queue")

    def volume(self, level=None, change=None):
        """Sets the volume to `level`, or changes it by `change`, within 0 to
        100. While ducked, that's the volume it goes back to."""
        if (level is None) == (change is None):
            raise Refused("send a level from 0 to 100, or a change, like 10 or -10")
        if change is not None and (not isinstance(change, int) or isinstance(change, bool)):
            raise Refused("change must be a whole number, like 10 or -10")
        with self._lock:
            self.level = (
                _level(level, "level") if level is not None else max(0, min(100, self.level + change))
            )
            self._apply_volume()
            return self.status()

    def duck(self, level=None):
        """Lowers the volume to `level` (duck_level by default) until unduck.
        Ducking again changes nothing."""
        level = self.duck_level if level is None else _level(level, "level")
        with self._lock:
            if self.ducked_to is None:
                self.ducked_to = level
                self._apply_volume()
            return self.status()

    def unduck(self):
        """Back to the volume before duck."""
        with self._lock:
            if self.ducked_to is not None:
                self.ducked_to = None
                self._apply_volume()
            return self.status()
