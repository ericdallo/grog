"""Settings: built-in defaults deep-merged with an optional JSON file, whose
keys are lowerCamelCase, as written in the NixOS module."""

import json
import os
import re

from . import fillers, pocket, supertonic
from .speech import parakeet_files

# The text to speech engines (tts.engine).
ENGINES = ("pocket", "supertonic", "piper")
# The voice of each without a tts.voice: Pocket's is a voice its model has
# (body/packages/voice-models.nix); Piper's voices are files, and it has none.
VOICES = {"pocket": "deep-calm", "supertonic": "M1"}
# How fast each speaks without a tts.speed: Supertonic's M1 is slow on its own
# (~140 words a minute), and the user liked it at 1.4 (~200); Piper's voices
# speak at a normal pace. Pocket TTS has no speed: its voices speak at the
# pace of the recording they were cloned from.
SPEEDS = {"supertonic": 1.4, "piper": 1.0}

DEFAULTS = {
    # grog's HTTP API.
    "grogUrl": "http://127.0.0.1:8484",
    # The grog conversation voice turns go to: grog forgets it after
    # conversationTtlMinutes without turns.
    "conversationId": "voice",
    # What to start speaking to it with, like "Alexa, ...": a phrase, or a
    # list of them. null: grog's name (its `name` setting). false: none,
    # anything heard gets an answer.
    "wakeWord": None,
    # PipeWire node names, as `wpctl status -n` shows them. null: the defaults.
    "mic": None,
    "speaker": None,
    "stt": {
        # Directory of a Parakeet TDT model for sherpa-onnx, int8 or fp32.
        "model": None,
        # On grognix's 4 cores, 3 are slower than 2 (by 0.3 to 0.4 s for a few
        # seconds of speech), as the waiting threads of its three models spin.
        "threads": 2,
        # Partials' (see partialMs): another Parakeet's directory, an int8
        # one where finals are fp32, say (~1.3 GB less: they only find grog's
        # name early, and show what's said). null: model's.
        "partialModel": None,
        # Partials' CPU threads, fewer than finals' (they run while grog
        # speaks, beside text to speech).
        "partialThreads": 2,
    },
    "tts": {
        # "pocket" (Pocket TTS, in a cloned voice), "supertonic" (Supertonic 3)
        # or "piper".
        "engine": "pocket",
        # Pocket: one of its model's voices (voices/), or a voice file (.npz).
        # Supertonic: one of its voices (F1 to F5, M1 to M5), or a voice style
        # file (.json). Piper: a voice, its .onnx file with the .onnx.json next
        # to it. null: the engine's (see VOICES).
        "voice": None,
        # How fast it speaks: 1 is the voice's own pace, 1.2 a fifth faster.
        # null: the engine's (see SPEEDS); Pocket's only.
        "speed": None,
        # Pocket: its directory, as voice/tools/export_pocket.py makes it.
        # Supertonic: its directory, as in Supertone/supertonic-3 (onnx/ and
        # voice_styles/).
        "model": None,
        # Supertonic: the language it reads ("pt", "en", ...), the one grog
        # answers in.
        "language": "pt",
        # Supertonic: steps of its flow. Fewer are faster, and worse: with 3 it
        # drops words.
        "steps": 5,
        # Pocket and Supertonic: CPU threads. On grognix's 4 cores, 4 are the
        # fastest: nothing is transcribed while it talks, unless heard over it
        # (see echo.py).
        "threads": 4,
    },
    # Silence after speech that ends an utterance.
    "endSilenceMs": 600,
    # A pause this long starts transcribing in the background, in case it
    # turns out to be the end. 0: wait for the end.
    "speculateMs": 250,
    # While someone talks, what they said so far is transcribed over and over,
    # one at a time, with at least this much more of it each time: grog's name
    # is heard before they're done, and what's said to grog shows as it comes.
    # 0: only at pauses.
    "partialMs": 300,
    # Longer utterances are cut.
    "maxUtteranceSeconds": 15,
    # After an answer, for this long, what's said from where whoever grog
    # answered talked from needs no wake word (see loop.Voice.follow), as far
    # as a ReSpeaker XVF3800 tells where speech comes from. 0: never.
    "followUpSeconds": 8,
    # What it says while grog looks something up before saying anything (see
    # fillers.py): true, the built-in phrases in the language its voice
    # reads; false, nothing; or its own, by kind ({"search": [...], "check":
    # [...]}, a kind left out getting none).
    "fillers": True,
    # While grog plays music, the VAD takes it for speech: what it hears then,
    # until it hears its name, is cut into windows this long, each starting a
    # second before the one before ends, and its name looked for in each.
    "musicUtteranceSeconds": 4,
    # A ReSpeaker XVF3800's (see ring.py).
    "ring": {
        # Its angle for whoever faces grog's screen, from 0 to 359 (seen from
        # above, clockwise from its USB port): grog's face looks at who talks
        # to it as they're to its side of it. null: where people talked to
        # grog from lately.
        "front": None,
    },
}

# The shortest musicUtteranceSeconds: windows overlap by a second
# (ears.MUSIC_OVERLAP_S), so each starts at least a second after the one
# before.
MIN_MUSIC_UTTERANCE_S = 2


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


def _int(v, minimum):
    return isinstance(v, int) and not isinstance(v, bool) and v >= minimum


def _number(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool) and v > 0


def _fillers(v):
    return isinstance(v, bool) or (
        isinstance(v, dict)
        and all(
            kind in fillers.KINDS and isinstance(said, list) and all(_non_blank(s) for s in said)
            for kind, said in v.items()
        )
    )


CHECKS = [
    (
        "grogUrl",
        lambda v: isinstance(v, str) and bool(re.fullmatch(r"https?://\S+", v)),
        'a URL, like "http://127.0.0.1:8484"',
    ),
    ("conversationId", _non_blank, "a conversation id"),
    (
        "wakeWord",
        lambda v: (
            v is None
            or v is False
            or _non_blank(v)
            or (isinstance(v, list) and bool(v) and all(_non_blank(p) for p in v))
        ),
        "a phrase, a list of phrases, false or null",
    ),
    ("mic", _optional(_non_blank), "a PipeWire node name, or null"),
    ("speaker", _optional(_non_blank), "a PipeWire node name, or null"),
    (
        "stt.model",
        lambda v: isinstance(v, str) and parakeet_files(v) is not None,
        "a Parakeet model directory (encoder, decoder and joiner, .int8.onnx or .onnx, and tokens.txt)",
    ),
    ("stt.threads", lambda v: _int(v, 1), "a positive integer"),
    (
        "stt.partialModel",
        lambda v: v is None or (isinstance(v, str) and parakeet_files(v) is not None),
        "a Parakeet model directory, or null",
    ),
    ("stt.partialThreads", lambda v: _int(v, 1), "a positive integer"),
    ("tts.engine", lambda v: v in ENGINES, '"pocket", "supertonic" or "piper"'),
    ("endSilenceMs", lambda v: _int(v, 1), "a positive integer"),
    ("speculateMs", lambda v: _int(v, 0), "an integer, 0 or more"),
    ("partialMs", lambda v: _int(v, 0), "an integer, 0 or more"),
    ("maxUtteranceSeconds", _number, "a positive number"),
    (
        "followUpSeconds",
        lambda v: isinstance(v, (int, float)) and not isinstance(v, bool) and v >= 0,
        "a number, 0 or more",
    ),
    (
        "fillers",
        _fillers,
        'true, false, or phrases by kind, like {"search": ["Buscando."], "check": ["Checando."]}',
    ),
    (
        "ring.front",
        lambda v: v is None or (isinstance(v, (int, float)) and not isinstance(v, bool) and 0 <= v < 360),
        "an angle from 0 to 359, or null",
    ),
]

# What a Supertonic model directory holds (see speech.Supertonic).
SUPERTONIC_FILES = tuple(
    f"onnx/{name}"
    for name in (
        "duration_predictor.onnx",
        "text_encoder.onnx",
        "vector_estimator.onnx",
        "vocoder.onnx",
        "tts.json",
        "unicode_indexer.json",
    )
)


# What a Pocket TTS model directory holds (see speech.Pocket), its voices
# aside.
POCKET_FILES = ("flow_lm.onnx", "mimi_decoder.onnx", "tokenizer.model", "pocket.json")


def voice(tts):
    """The voice the `tts` settings pick: theirs, or the engine's (VOICES)."""
    return VOICES.get(tts["engine"]) if tts["voice"] is None else tts["voice"]


def _piper_voice(v):
    return isinstance(v, str) and os.path.isfile(v) and os.path.isfile(v + ".json")


def _supertonic_model(v):
    return isinstance(v, str) and all(os.path.isfile(os.path.join(v, f)) for f in SUPERTONIC_FILES)


def _supertonic_voice(v, model):
    """A style file, or one of its voices, which `model` has (when it's a
    model at all: if not, that's what's wrong)."""
    if not _non_blank(v):
        return False
    if v.endswith(".json"):
        return os.path.isfile(v)
    return v in supertonic.VOICES and (
        not _supertonic_model(model) or os.path.isfile(supertonic.voice_style(model, v))
    )


def _pocket_model(v):
    return isinstance(v, str) and all(os.path.isfile(os.path.join(v, f)) for f in POCKET_FILES)


def _pocket_voice(v, model):
    """A voice file, or one of `model`'s voices (when it's a model at all: if
    not, that's what's wrong)."""
    if not _non_blank(v):
        return False
    if v.endswith(".npz"):
        return os.path.isfile(v)
    return not _pocket_model(model) or v in pocket.voices(model)


def _speed(v):
    return v is None or (_number(v) and 0.5 <= v <= 2)


def _tts_checks(settings):
    """The checks of the settings of the text to speech engine in use, in
    the order of DEFAULTS."""
    engine, model = _get(settings, "tts.engine"), _get(settings, "tts.model")
    default = VOICES.get(engine)
    speed = ("tts.speed", _speed, "a number from 0.5 to 2, or null")
    threads = ("tts.threads", lambda v: _int(v, 1), "a positive integer")
    if engine == "piper":
        return [("tts.voice", _piper_voice, "a Piper voice (.onnx, with its .onnx.json next to it)"), speed]
    if engine == "pocket":
        names = ", ".join(pocket.voices(model)) if _pocket_model(model) else "in its voices/"
        return [
            (
                "tts.voice",
                lambda v: _pocket_voice(default if v is None else v, model),
                f"one of its model's voices ({names}), or a voice file (.npz)",
            ),
            ("tts.speed", lambda v: v is None, "null: Pocket TTS speaks at its voice's own pace"),
            (
                "tts.model",
                _pocket_model,
                "a Pocket TTS model directory (flow_lm.onnx, mimi_decoder.onnx, tokenizer.model and "
                "pocket.json, as voice/tools/export_pocket.py makes them)",
            ),
            threads,
        ]
    if engine != "supertonic":
        return []
    return [
        (
            "tts.voice",
            lambda v: _supertonic_voice(default if v is None else v, model),
            "one of Supertonic's voices (F1 to F5, M1 to M5), or a voice style file (.json)",
        ),
        speed,
        (
            "tts.model",
            _supertonic_model,
            "a Supertonic model directory (onnx/ and voice_styles/, as in Supertone/supertonic-3)",
        ),
        ("tts.language", lambda v: v in supertonic.LANGUAGES, 'a language Supertonic reads, like "pt"'),
        ("tts.steps", lambda v: _int(v, 1), "a positive integer"),
        threads,
    ]


def _music_check(settings):
    """musicUtteranceSeconds: from MIN_MUSIC_UTTERANCE_S to
    maxUtteranceSeconds, as longer windows would be cut first."""
    longest = _get(settings, "maxUtteranceSeconds")
    if not _number(longest):
        longest = None
    return (
        "musicUtteranceSeconds",
        lambda v: _number(v) and v >= MIN_MUSIC_UTTERANCE_S and (longest is None or v <= longest),
        f"a number from {MIN_MUSIC_UTTERANCE_S} to maxUtteranceSeconds ({longest})"
        if longest is not None
        else f"a number, {MIN_MUSIC_UTTERANCE_S} or more",
    )


def _checks(settings):
    """CHECKS, with those of the text to speech engine's settings after
    tts.engine, and musicUtteranceSeconds's."""
    at = next(i for i, check in enumerate(CHECKS) if check[0] == "tts.engine") + 1
    return CHECKS[:at] + _tts_checks(settings) + CHECKS[at:] + [_music_check(settings)]


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
    for path, ok, expected in _checks(settings):
        v = _get(settings, path)
        if not ok(v):
            out.append(f"{path}: expected {expected}, got {'nothing' if v is None else json.dumps(v)}")
    return out
