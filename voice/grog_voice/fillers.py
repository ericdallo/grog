"""What grog-voice says while grog looks something up before saying anything
(the web, the weather, the home's state): a short phrase, "Buscando." or
"Checando.", made with its own voice once, as it starts, so that it plays at
once. A tool with a kind of phrase (see kind) gets one; the rest, which act,
get none."""

import logging
import time

from .audio import Silence
from .mouth import calmed, where

log = logging.getLogger("grog_voice")

# The kinds of phrases: for searching the web, and for checking something.
KINDS = ("search", "check")

# The phrases of each kind, by the language its voice reads (see phrases).
PHRASES = {
    "pt": {"search": ["Buscando.", "Procurando."], "check": ["Checando.", "Tô vendo."]},
    "en": {"search": ["Searching.", "Looking it up."], "check": ["Checking.", "Let me see."]},
}

# grog's tools that check something: the weather, and how its machine is.
CHECKING = ("weather", "system_status")


def kind(tool):
    """The kind of phrase grog's tool named `tool` calls for: search for a
    web search, check for a tool checking something (CHECKING, and Home
    Assistant's GetLiveContext, the home's state, whatever its prefix), or
    None."""
    if tool == "web_search":
        return "search"
    if tool in CHECKING or tool.endswith("__GetLiveContext"):
        return "check"
    return None


def phrases(setting, language):
    """The phrases the fillers setting picks, by kind: its own, the built-in
    ones in `language` (true; none when it has none), or none (false)."""
    if setting is True:
        return PHRASES.get(language, {})
    return setting or {}


def _sound(tts, text):
    """The sound of `text` said by `tts`, calmed (see mouth.calmed), without
    the pause after it."""
    return b"".join(pcm for pcm in tts(calmed(text)) if not isinstance(pcm, Silence))


class Fillers:
    """Its `phrases` ({kind: [phrase, ...]}), made into sound with make. Called
    with a tool's name: the sound of a phrase of its kind, taking turns, or
    None (none of its kind, or not made)."""

    def __init__(self, phrases):
        self.phrases = phrases
        self.made = {}
        self._said = {}  # how many of each kind were said

    def make(self, tts):
        """Makes its phrases into sound with `tts`: none when that fails,
        saying how it did."""
        if not self.phrases:
            return
        started = time.monotonic()
        try:
            made = {which: [_sound(tts, text) for text in texts] for which, texts in self.phrases.items()}
        except Exception as e:
            log.warning("couldn't make what it says while grog looks something up: %s", where(e))
            return
        self.made = {which: [pcm for pcm in sounds if pcm] for which, sounds in made.items()}
        log.info("fillers made in %.1f s", time.monotonic() - started)

    def __call__(self, tool):
        which = kind(tool)
        sounds = self.made.get(which)
        if not sounds:
            return None
        said = self._said.get(which, 0)
        self._said[which] = said + 1
        return sounds[said % len(sounds)]
