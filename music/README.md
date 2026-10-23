# music

grog-music, the player grog plays music with. It runs in the session of
the user with the speaker, so music comes out of their default sink: grog's
speaker, or a Bluetooth speaker paired with it. It plays with
[mpv](https://mpv.io), which asks [yt-dlp](https://github.com/yt-dlp/yt-dlp)
for YouTube's audio, and it searches YouTube Music with
[ytmusicapi](https://github.com/sigma67/ytmusicapi). What to play is up to
the [brain](../brain/README.md#music): its `music` tool, whose providers are
integrations (YouTube Music first).

## How grog uses it

grog connects to its socket (`music.socket`), sends one JSON object on a
line, reads one back, and hangs up. Keys are lowerCamelCase. Replies are
`{"ok": true, ...}`, or `{"ok": false, "error": "..."}` with a plain
sentence.

The player takes playable items from any provider: `{url, title?, artist?,
album?}`, where `url` is http(s) (YouTube's go through yt-dlp, anything else,
like a radio, plays as it is) or `file:///` and an absolute path.

| Command | Takes | Does |
| --- | --- | --- |
| `status` | | `state` (`playing`, `paused` or `idle`), `title`, `artist`, `album`, `positionS`, `durationS`, `volume` (0 to 100), `ducked`, `index` (from 0), `count` |
| `play` | `items`, `shuffle` | Replaces the queue and plays it |
| `enqueue` | `items` | Adds to the queue (plays them when nothing plays) |
| `pause`, `resume`, `next`, `previous` | | |
| `stop` | | Stops and clears the queue |
| `volume` | `level` (0 to 100) or `change` (±n) | While ducked, sets the volume it goes back to |
| `duck` | `level` (`duckLevel` by default) | Lowers the volume, while someone talks to grog or it speaks. Twice changes nothing |
| `unduck` | | Back to the volume before |

All of them answer with the status. YouTube Music's commands:

| Command | Takes | Does |
| --- | --- | --- |
| `youtube-music.search` | `query`, `kind` (`top`, `songs`, `albums`, `playlists`, `artists`) | `results`: up to 10 `{kind, id, title, artist?, album?, durationS?}` |
| `youtube-music.play` | `query` or `id`, `kind`, `shuffle`, `auth` | A song then its up next (like the app), an album's or a playlist's tracks, an artist's top songs; `kind: "my-playlist"` with a `query`, the user's playlist named most like it; `kind: "liked"`, their likes. Without a `kind`, the top result. Up to 100 in the queue. Answers `playing {title, artist}` and the status |
| `youtube-music.library` | `kind` (`playlists`, `liked`, `albums`, `history`), `auth` | `items`: up to 25 `{kind, id, title, artist?, count?}` |

`auth` is the user's Google sign-in, kept fresh by grog and never written
to disk here: `{"accessToken", "refreshToken", "expiresAt"` (epoch
milliseconds), `"clientId", "clientSecret", "scope", "tokenType"}`, from a
Google client of type "TVs and Limited Input devices" (the one grog was
given, or the user's) whose project has the YouTube Data API v3 enabled. Searching and
playing need none: they're anonymous. YouTube Music refuses such tokens
for now (HTTP 400, sigma67/ytmusicapi#813): it's tried first, and once it
refuses, not again for 6 hours. Meanwhile the user's playlists, a
playlist's songs and their likes come from the Data API
([`google.py`](grog_music/google.py); likes there are all of YouTube's,
music or not), and their saved albums and history can't be read.

To try it:

```sh
grog-music --socket /run/grog-music/music.sock send '{"command": "youtube-music.play", "query": "daft punk"}'
grog-music --socket /run/grog-music/music.sock send '{"command": "status"}'
```

Without the network, a local file plays: `{"command": "play", "items":
[{"url": "file:///path/to/song.ogg"}]}`.

## Running it

On NixOS, `services.grog.music.enable` runs it as a user service of
`services.grog.music.user` (`services.grog.user`), its socket at `/run/grog-music/music.sock`, which
only that user and grog reach. It starts mpv once, and again whenever mpv
dies. YouTube changes often, and yt-dlp and ytmusicapi follow: when it
stops playing, a newer nixpkgs (or ytmusicapi, pinned in
`body/packages/grog-music.nix` at 1.12.3, as nixpkgs' 1.12.0 breaks on
YouTube Music's answers) usually fixes it. yt-dlp needs a JavaScript runtime
and challenge solver scripts for YouTube now: nixpkgs' comes with Deno and
yt-dlp-ejs.

grog ducks the music, to 20, while someone talks to it and while it
answers aloud, from what grog-voice says it's doing. On NixOS it plays into
grog's echo canceller (`audioDevice`), so that grog hears its name over it;
without it, grog may hear its name less over loud music.

## Privacy

It logs which command ran, how it went and how long it took, never what
was asked, found or played. mpv runs with nothing on its terminal. Tokens
only live in memory, for the request they came with.

## Settings

From `services.grog.music.settings`, or a JSON file (`grog-music --config`,
or `$GROG_MUSIC_CONFIG`), merged over the defaults in
[`config.py`](grog_music/config.py).

| Setting | Default | Notes |
| --- | --- | --- |
| `socket` | `null` | Where grog asks it things. Also `--socket`. On NixOS: `/run/grog-music/music.sock` |
| `mpv`, `ytdlp` | `"mpv"`, `"yt-dlp"` | The programs |
| `mpvSocket` | `null` | mpv's control socket. `null`: `grog-music/mpv.sock` in `$XDG_RUNTIME_DIR` |
| `audioDevice` | `null` | Where mpv plays (its `--audio-device`). On NixOS, `"pipewire/grog-music-out"`: grog's echo canceller, which keeps the music out of grog's mic ([body/](../body/README.md#echo-cancellation)), and falls back to the default sink when it isn't there. `null`: the session's default sink |
| `volume` | `100` | mpv's volume at start, on top of the speaker's |
| `duckLevel` | `20` | Where `duck` lowers it to when it doesn't say (grog doesn't) |
| `maxQueue` | `100` | |
| `youtubeMusic.language` | `"en"` | The language of YouTube Music's titles. ytmusicapi reads English best: in some others it takes play counts for artists |
| `youtubeMusic.location` | `""` | A country code, like `"BR"`, for what it finds. `""`: YouTube's guess |

## Development

The tests only need the standard library, as ytmusicapi loads lazily and
mpv and YouTube are faked: `python3 -m unittest discover -s tests`.
