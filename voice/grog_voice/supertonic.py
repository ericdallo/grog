"""What Supertonic 3 reads, and where its voices are (see speech.Supertonic):
plain Python, like Supertone's own example (py/helper.py in
supertone-inc/supertonic, MIT)."""

import math
import os
import re
from unicodedata import normalize

# The languages it reads.
LANGUAGES = (
    "ar", "bg", "cs", "da", "de", "el", "en", "es", "et", "fi", "fr", "hi", "hr", "hu", "id", "it",
    "ja", "ko", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi",
)  # fmt: skip
# Its voices, in the model's voice_styles/.
VOICES = tuple(f"{kind}{n}" for kind in "FM" for n in range(1, 6))
# It reads longer texts badly: they're read in pieces.
MAX_CHARS = {"ja": 120, "ko": 120}
DEFAULT_MAX_CHARS = 300

_PLAIN = str.maketrans(
    {
        "–": "-", "‑": "-", "—": "-", "_": " ", "“": '"', "”": '"', "‘": "'", "’": "'", "´": "'",
        "`": "'", "[": " ", "]": " ", "|": " ", "/": " ", "#": " ", "→": " ", "←": " ",
    }
)  # fmt: skip
_SPACE_BEFORE = re.compile(r" ([,.!?;:'])")
_ENDED = re.compile(r"[.!?;:,'\")\]}…。」』】〉》›»]$")
_SENTENCE_END = re.compile(r"[.!?…]+[\"')\]»”]*\s")
_CLAUSE_END = re.compile(r"[,;:]\s")
_SPACE = re.compile(r"\s")


def prepare(text, language):
    """`text` as it's read: decomposed (NFKD), with plain punctuation and
    single spaces, ending with punctuation, between language tags."""
    text = normalize("NFKD", text).translate(_PLAIN)
    text = _SPACE_BEFORE.sub(r"\1", " ".join(text.split()))
    if not _ENDED.search(text):
        text += "."
    return f"<{language}>{text}</{language}>"


def symbols(text, indexer):
    """The ids of `text`'s characters, from the model's unicode_indexer.json
    (a list, by code point), without those it doesn't know (emoji and such)."""
    ids = (indexer[ord(c)] if ord(c) < len(indexer) else -1 for c in text)
    return [i for i in ids if i >= 0]


def pieces(text, language):
    """`text` in pieces short enough to read well, cut after the last
    sentence end that fits, or else comma, or else space."""
    limit = MAX_CHARS.get(language, DEFAULT_MAX_CHARS)
    rest, out = " ".join(text.split()), []
    while len(rest) > limit:
        head = rest[: limit + 1]
        ends = next(
            (
                cuts
                for pattern in (_SENTENCE_END, _CLAUSE_END, _SPACE)
                if (cuts := list(pattern.finditer(head)))
            ),
            [],
        )
        cut = ends[-1].end() if ends else limit
        out.append(rest[:cut].strip())
        rest = rest[cut:].strip()
    return out + [rest] if rest else out


def voice_style(model, voice):
    """The style file of `voice`: one of VOICES, in `model`'s voice_styles/,
    or a .json of its own (Supertone's Voice Builder makes them)."""
    return voice if voice.endswith(".json") else os.path.join(model, "voice_styles", f"{voice}.json")


def latent_frames(samples, frame):
    """How many latent frames make `samples` of audio, `frame` samples each."""
    return max(1, math.ceil(samples / frame))
