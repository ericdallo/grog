"""Settings: built-in defaults deep-merged with an optional JSON file, whose
keys are lowerCamelCase, as written in the NixOS module."""

import json

# ytmusicapi's languages (ytmusicapi.constants.SUPPORTED_LANGUAGES): what
# YouTube Music's titles and categories come in.
LANGUAGES = (
    "ar",
    "de",
    "en",
    "es",
    "fr",
    "hi",
    "it",
    "ja",
    "ko",
    "nl",
    "pt",
    "ru",
    "tr",
    "ur",
    "zh_CN",
    "zh_TW",
)

DEFAULTS = {
    # Where grog asks it things (also `grog-music --socket`).
    "socket": None,
    # The programs it plays with: mpv, and yt-dlp, which mpv asks for
    # YouTube's audio.
    "mpv": "mpv",
    "ytdlp": "yt-dlp",
    # mpv's own control socket. null: grog-music/mpv.sock in $XDG_RUNTIME_DIR,
    # a directory only its user reaches.
    "mpvSocket": None,
    # mpv's volume at start, 0 to 100, on top of the speaker's.
    "volume": 100,
    # Where `duck` lowers it to when it doesn't say.
    "duckLevel": 20,
    # The most a queue holds.
    "maxQueue": 100,
    "youtubeMusic": {
        # The language of YouTube Music's titles and categories. ytmusicapi
        # reads English best: in some others it takes play counts for
        # artists.
        "language": "en",
        # A country code, like "BR", for what it finds. "": YouTube's guess.
        "location": "",
    },
}


class ConfigError(Exception):
    """The settings file can't be used."""


def deep_merge(base, override):
    out = dict(base)
    for k, v in override.items():
        out[k] = deep_merge(base[k], v) if isinstance(base.get(k), dict) and isinstance(v, dict) else v
    return out


def load(path):
    """The defaults merged with the JSON file at `path`, when given."""
    if not path:
        return deep_merge(DEFAULTS, {})
    try:
        with open(path, encoding="utf-8") as f:
            text = f.read()
    except OSError as e:
        raise ConfigError(f"Couldn't read the settings file {path}: {e.strerror}") from e
    try:
        settings = json.loads(text)
    except ValueError as e:
        raise ConfigError(f"The settings file {path} isn't valid JSON: {e}") from e
    if not isinstance(settings, dict):
        raise ConfigError(f"The settings file {path} must hold a JSON object.")
    return deep_merge(DEFAULTS, settings)


def _non_blank(v):
    return isinstance(v, str) and bool(v.strip())


def _optional(pred):
    return lambda v: v is None or pred(v)


def _int(v, minimum, maximum):
    return isinstance(v, int) and not isinstance(v, bool) and minimum <= v <= maximum


CHECKS = [
    ("socket", _non_blank, "a path (or --socket)"),
    ("mpv", _non_blank, "a program"),
    ("ytdlp", _non_blank, "a program"),
    ("mpvSocket", _optional(_non_blank), "a path, or null"),
    ("volume", lambda v: _int(v, 0, 100), "an integer from 0 to 100"),
    ("duckLevel", lambda v: _int(v, 0, 100), "an integer from 0 to 100"),
    ("maxQueue", lambda v: _int(v, 1, 1000), "an integer from 1 to 1000"),
    ("youtubeMusic.language", lambda v: v in LANGUAGES, f"one of {', '.join(LANGUAGES)}"),
    (
        "youtubeMusic.location",
        lambda v: isinstance(v, str) and (v == "" or (len(v) == 2 and v.isalpha() and v.isupper())),
        'a country code, like "BR", or ""',
    ),
]


def _get(settings, path):
    v = settings
    for k in path.split("."):
        v = v.get(k) if isinstance(v, dict) else None
    return v


def _unknown(settings, defaults, prefix=""):
    for k, v in settings.items():
        if k not in defaults:
            yield prefix + k
        elif isinstance(defaults[k], dict) and isinstance(v, dict):
            yield from _unknown(v, defaults[k], f"{prefix}{k}.")


def problems(settings):
    """What's wrong with `settings`, naming them the way they're written."""
    out = [f"unknown setting: {path}" for path in _unknown(settings, DEFAULTS)]
    for path, ok, expected in CHECKS:
        v = _get(settings, path)
        if not ok(v):
            out.append(f"{path}: expected {expected}, got {'nothing' if v is None else json.dumps(v)}")
    return out
