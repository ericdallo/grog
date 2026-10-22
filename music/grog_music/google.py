"""The user's YouTube library through YouTube's Data API v3, with the Google
token grog hands over: their playlists, a playlist's videos, and what they
liked. YouTube Music's own endpoints refuse ytmusicapi's OAuth tokens (HTTP
400 since August 2025, sigma67/ytmusicapi#813) while the Data API takes
them. The token is only kept in memory, for the request."""

import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request

from . import Refused

API_URL = "https://www.googleapis.com/youtube/v3"
TOKEN_URL = "https://oauth2.googleapis.com/token"
RECONNECT = "your YouTube Music account needs connecting again"
# Refreshed this long before it expires.
REFRESH_EARLY_S = 60


class AccountError(Refused):
    """The account's token doesn't work anymore."""


def artist_of_channel(name):
    """YouTube's auto-generated channels of artists are "<artist> - Topic"."""
    if not isinstance(name, str):
        return None
    return name[: -len(" - Topic")] if name.endswith(" - Topic") else name


def iso_seconds(duration):
    """Seconds of an ISO 8601 duration, like "PT3M20S", or None."""
    m = re.fullmatch(r"P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?", duration or "")
    if not m or not any(m.groups()):
        return None
    d, h, mins, s = (int(g or 0) for g in m.groups())
    return ((d * 24 + h) * 60 + mins) * 60 + s


class Google:
    """YouTube's Data API at `api_url`, refreshing tokens at `token_url`."""

    def __init__(self, api_url=API_URL, token_url=TOKEN_URL, timeout_s=15.0, clock=time.time):
        self.api_url, self.token_url, self.timeout_s, self.clock = api_url, token_url, timeout_s, clock

    def access_token(self, auth):
        """`auth`'s access token, refreshed first (into `auth`) when it's about
        to expire."""
        if auth["expiresAt"] / 1000 - self.clock() > REFRESH_EARLY_S:
            return auth["accessToken"]
        body = urllib.parse.urlencode(
            {
                "client_id": auth["clientId"],
                "client_secret": auth["clientSecret"],
                "refresh_token": auth["refreshToken"],
                "grant_type": "refresh_token",
            }
        ).encode()
        try:
            fresh = self._json(urllib.request.Request(self.token_url, data=body, method="POST"))
        except urllib.error.HTTPError as e:
            e.close()
            raise AccountError(RECONNECT) if e.code in (400, 401) else Refused("Google didn't answer") from e
        if not isinstance(fresh.get("access_token"), str):
            raise Refused("Google didn't answer")
        auth["accessToken"] = fresh["access_token"]
        auth["expiresAt"] = int((self.clock() + int(fresh.get("expires_in") or 3600)) * 1000)
        return auth["accessToken"]

    def _json(self, request):
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_s) as response:
                return json.loads(response.read())
        except urllib.error.HTTPError:
            raise
        except (OSError, ValueError) as e:
            raise Refused("YouTube didn't answer") from e

    def _get(self, auth, path, params):
        url = f"{self.api_url}/{path}?{urllib.parse.urlencode(params)}"
        request = urllib.request.Request(url, headers={"Authorization": f"Bearer {self.access_token(auth)}"})
        try:
            return self._json(request)
        except urllib.error.HTTPError as e:
            e.close()
            if e.code == 401:
                raise AccountError(RECONNECT) from e
            if e.code == 403:
                raise Refused(
                    "YouTube refused grog: is the YouTube Data API enabled in your Google Cloud project?"
                ) from e
            if e.code == 404:
                raise Refused("nothing found for that") from e
            raise Refused("YouTube didn't answer") from e

    def _pages(self, auth, path, params, limit):
        out, token = [], None
        while len(out) < limit:
            page = self._get(
                auth, path, params | {"maxResults": 50} | ({"pageToken": token} if token else {})
            )
            out.extend(i for i in page.get("items") or [] if isinstance(i, dict))
            token = page.get("nextPageToken")
            if not token:
                break
        return out[:limit]

    def playlists(self, auth, limit=200):
        """The user's playlists: [{kind, id, title, count?}]."""
        out = []
        for p in self._pages(auth, "playlists", {"part": "snippet,contentDetails", "mine": "true"}, limit):
            title = (p.get("snippet") or {}).get("title")
            if isinstance(p.get("id"), str) and isinstance(title, str):
                count = (p.get("contentDetails") or {}).get("itemCount")
                out.append({"kind": "playlist", "id": p["id"], "title": title} | _maybe("count", count))
        return out

    def playlist_tracks(self, auth, playlist_id, limit=100):
        """A playlist's videos: [{kind "song", id, title, artist?}], without the
        deleted or private ones."""
        out = []
        params = {"part": "snippet,contentDetails", "playlistId": playlist_id}
        for i in self._pages(auth, "playlistItems", params, limit):
            snippet = i.get("snippet") or {}
            video = (i.get("contentDetails") or {}).get("videoId")
            owner = snippet.get("videoOwnerChannelTitle")
            if isinstance(video, str) and isinstance(snippet.get("title"), str) and owner:
                out.append(
                    {"kind": "song", "id": video, "title": snippet["title"]}
                    | _maybe("artist", artist_of_channel(owner))
                )
        return out

    def liked(self, auth, limit=100):
        """What the user liked on YouTube (YouTube Music's likes are YouTube's):
        [{kind "song", id, title, artist?, durationS?}]."""
        out = []
        params = {"part": "snippet,contentDetails", "myRating": "like"}
        for v in self._pages(auth, "videos", params, limit):
            snippet = v.get("snippet") or {}
            if isinstance(v.get("id"), str) and isinstance(snippet.get("title"), str):
                out.append(
                    {"kind": "song", "id": v["id"], "title": snippet["title"]}
                    | _maybe("artist", artist_of_channel(snippet.get("channelTitle")))
                    | _maybe("durationS", iso_seconds((v.get("contentDetails") or {}).get("duration")))
                )
        return out


def _maybe(key, value):
    return {key: value} if value not in (None, "") else {}
