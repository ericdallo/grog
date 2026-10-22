"""grog's player: a queue of playable items, from any provider, played by
mpv, with its volume and ducking (lower while grog listens or speaks, then
back)."""

import random
import threading
from urllib.parse import urlsplit

from . import Refused
from .mpv import MpvError

# What YouTube's hosts serve goes straight to yt-dlp (mpv's ytdl:// prefix),
# rather than mpv first fetching the page, failing, then asking yt-dlp.
YOUTUBE_HOSTS = ("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")
MAX_URL, MAX_TEXT = 4096, 300
ITEMS = 'send items: a list of {"url": "..."}, with a title, artist and album when known'


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


def mpv_url(url):
    """What mpv is told to load for `url`."""
    return "ytdl://" + url if (urlsplit(url).hostname or "").lower() in YOUTUBE_HOSTS else url


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
        out.append(
            {"url": item["url"]}
            | {
                k: item[k].strip()[:MAX_TEXT]
                for k in ("title", "artist", "album")
                if isinstance(item.get(k), str) and item[k].strip()
            }
        )
    return out


def _level(v, name):
    if not isinstance(v, int) or isinstance(v, bool) or not 0 <= v <= 100:
        raise Refused(f"{name} must be a whole number from 0 to 100")
    return v


def _seconds(v):
    return round(v, 1) if isinstance(v, (int, float)) and not isinstance(v, bool) and v >= 0 else None


class Player:
    """mpv (an Mpv, set as .mpv: call on_mpv_start each time it starts), its
    volume at `volume`, ducked to `duck_level` unless told another, holding at
    most `max_queue` items. `shuffle(items)` shuffles a list in place."""

    def __init__(self, mpv=None, volume=100, duck_level=20, max_queue=100, shuffle=random.shuffle):
        self.mpv, self.level, self.duck_level, self.max_queue = mpv, volume, duck_level, max_queue
        self._shuffle = shuffle
        self.ducked_to = None
        self.items = []
        self._lock = threading.RLock()

    def on_mpv_start(self):
        """A new mpv has nothing queued, and its own volume."""
        with self._lock:
            self.items = []
            self._apply_volume()

    def _effective_volume(self):
        return self.level if self.ducked_to is None else min(self.level, self.ducked_to)

    def _apply_volume(self):
        self.mpv.set("volume", self._effective_volume())

    def status(self):
        """{state "playing"|"paused"|"idle", title, artist, album, positionS,
        durationS, volume, ducked, index (from 0), count}, without what isn't
        known."""
        with self._lock:
            position = self.mpv.get("playlist-pos")
            count = self.mpv.get("playlist-count") or 0
            out = {"volume": self.level, "ducked": self.ducked_to is not None, "count": count}
            if not isinstance(position, int) or position < 0:
                return {"state": "idle"} | out
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
            return out | {k: v for k, v in known.items() if isinstance(v, (str, float, int)) and v != ""}

    def _idle(self):
        position = self.mpv.get("playlist-pos")
        return not isinstance(position, int) or position < 0

    def play(self, items, shuffle=False):
        """Replaces the queue with `items` ({url, title?, artist?, album?}),
        shuffled when asked, and plays them."""
        items = items_from(items, self.max_queue)
        if shuffle:
            self._shuffle(items)
        with self._lock:
            self.items = []
            for i, item in enumerate(items):
                self.mpv.command("loadfile", mpv_url(item["url"]), "replace" if i == 0 else "append")
                self.items.append(item)
            self.mpv.set("pause", False)
            return self.status()

    def enqueue(self, items):
        """Adds `items` at the end of the queue, playing them when nothing
        plays."""
        items = items_from(items, self.max_queue)
        with self._lock:
            room = self.max_queue - len(self.items)
            if room <= 0 and not self._idle():
                raise Refused(f"the queue is full: it holds {self.max_queue}")
            if self._idle():
                return self.play(items)
            for item in items[:room]:
                self.mpv.command("loadfile", mpv_url(item["url"]), "append-play")
                self.items.append(item)
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
        """Stops, and clears the queue."""
        with self._lock:
            self.mpv.command("stop")
            self.items = []
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
