"""Settings: built-in defaults deep-merged with an optional JSON file, whose
keys are lowerCamelCase, as written in the NixOS module."""

import json
import os
import re

from . import supertonic

# The text to speech engines (tts.engine).
ENGINES = ("supertonic", "piper")

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
        # Directory of a Parakeet TDT model for sherpa-onnx (int8).
        "model": None,
        # On grognix's 4 cores, 3 are slower than 2 (by 0.3 to 0.4 s for a few
        # seconds of speech), as the waiting threads of its three models spin.
        "threads": 2,
    },
    "tts": {
        # "supertonic" (Supertonic 3) or "piper".
        "engine": "supertonic",
        # Supertonic: one of its voices (F1 to F5, M1 to M5), or a voice style
        # file (.json). Piper: a voice, its .onnx file with the .onnx.json next
        # to it.
        "voice": "M1",
        # Supertonic: its directory, as in Supertone/supertonic-3 (onnx/ and
        # voice_styles/).
        "model": None,
        # Supertonic: the language it reads ("pt", "en", ...), the one grog
        # answers in.
        "language": "pt",
        # Supertonic: steps of its flow. Fewer are faster, and worse: with 3 it
        # drops words.
        "steps": 5,
        # Supertonic: CPU threads. On grognix's 4 cores, 4 are the fastest: it
        # doesn't listen while it talks.
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


def _int(v, minimum):
    return isinstance(v, int) and not isinstance(v, bool) and v >= minimum


def _number(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool) and v > 0


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
    ("stt.model", lambda v: isinstance(v, str) and os.path.isdir(v), "a Parakeet model directory"),
    ("stt.threads", lambda v: _int(v, 1), "a positive integer"),
    ("tts.engine", lambda v: v in ENGINES, '"supertonic" or "piper"'),
    ("endSilenceMs", lambda v: _int(v, 1), "a positive integer"),
    ("speculateMs", lambda v: _int(v, 0), "an integer, 0 or more"),
    ("partialMs", lambda v: _int(v, 0), "an integer, 0 or more"),
    ("maxUtteranceSeconds", _number, "a positive number"),
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


def _tts_checks(settings):
    """The checks of the settings of the text to speech engine in use."""
    engine, model = _get(settings, "tts.engine"), _get(settings, "tts.model")
    if engine == "piper":
        return [("tts.voice", _piper_voice, "a Piper voice (.onnx, with its .onnx.json next to it)")]
    if engine != "supertonic":
        return []
    return [
        (
            "tts.voice",
            lambda v: _supertonic_voice(v, model),
            "one of Supertonic's voices (F1 to F5, M1 to M5), or a voice style file (.json)",
        ),
        (
            "tts.model",
            _supertonic_model,
            "a Supertonic model directory (onnx/ and voice_styles/, as in Supertone/supertonic-3)",
        ),
        ("tts.language", lambda v: v in supertonic.LANGUAGES, 'a language Supertonic reads, like "pt"'),
        ("tts.steps", lambda v: _int(v, 1), "a positive integer"),
        ("tts.threads", lambda v: _int(v, 1), "a positive integer"),
    ]


def _checks(settings):
    """CHECKS, with those of the text to speech engine's settings after
    tts.engine."""
    at = next(i for i, check in enumerate(CHECKS) if check[0] == "tts.engine") + 1
    return CHECKS[:at] + _tts_checks(settings) + CHECKS[at:]


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
