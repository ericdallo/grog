"""Wake words: is an utterance addressed to grog? Matched on its transcript by
how words sound rather than how they're spelled: speech-to-text hears an
unusual name like "Grog" as "Groga", "Grogue" or "Crog", and from afar as
"Drog" or "Drogue", while "Droga" or "Bro" are other words.
"""

import re
import unicodedata

# Words that may come before the wake word: "Ei, Grog, ...", "Ok Grog ...".
MAX_LEADING_WORDS = 2

# Spellings of one sound (Portuguese first), to class symbols (upper case).
_SPELLINGS = [
    (r"gu(?=[ei])", "K"),  # "grogue"
    (r"qu", "K"),
    (r"c(?=[ei])", "S"),
    (r"g(?=[ei])", "J"),
    (r"[cs]h|x", "X"),
    (r"lh", "R"),
    (r"nh", "N"),
    (r"ph", "F"),
    (r"th", "T"),
    (r"h", ""),  # silent
]
# What's left: sounds alike, and that speech-to-text mixes up.
_CLASSES = str.maketrans({
    "c": "K", "g": "K", "k": "K", "q": "K",
    "b": "P", "p": "P",
    "d": "T", "t": "T",
    "f": "F", "v": "F",
    "s": "S", "z": "S",
    "j": "J",
    "l": "R", "r": "R",
    "m": "N", "n": "N",
    "w": "u", "y": "i",
    "e": "i", "o": "u",  # unstressed, they sound like i and u
})  # fmt: skip


def _plain(word):
    word = unicodedata.normalize("NFKD", word.lower())
    return re.sub(r"[^a-z0-9]", "", "".join(c for c in word if not unicodedata.combining(c)))


def sound(word):
    """A rough phonetic key of a word: letters that sound alike become one
    symbol, and a final vowel after a consonant goes, as speakers add one
    ("Grog" -> "grógui") and speech-to-text spells it ("Groga")."""
    key = _plain(word)
    for spelling, symbol in _SPELLINGS:
        key = re.sub(spelling, symbol, key)
    key = re.sub(r"(.)\1+", r"\1", key.translate(_CLASSES))
    if len(key) >= 3 and key[-1] in "aiu" and key[-2].isupper():
        key = key[:-1]
    return key


# Sounds that speech-to-text mixes up at the start of a word, more so from
# afar: "Grog" comes out as "Drog", "Drogue" or "Prog".
_PLOSIVES = "KPT"


def _alike(key, target, word):
    if key == target:
        return True
    # A final consonant may get lost too: "Gro".
    if len(target) >= 3 and target[-1].isupper() and key == target[:-1]:
        return True
    # Another plosive first: "Drog" for "Grog". Unless it ends in a, o or u, as
    # Portuguese words that sound like that mostly do: "droga", "troco", "truco".
    return (
        len(target) >= 3
        and target[0] in _PLOSIVES
        and key[:1] in _PLOSIVES
        and key[1:] == target[1:]
        and _plain(word)[-1:] not in "aou"
    )


class WakeWords:
    """Wake phrases (one word or more each, like "Grog" or "Ok computer")."""

    def __init__(self, phrases):
        self.phrases = [[sound(w) for w in re.findall(r"[^\W_]+", p)] for p in phrases]
        self.phrases = [p for p in self.phrases if p and all(p)]

    def match(self, text):
        """The command in `text` after a wake phrase near its start ("" when
        there's nothing after it), or None when it has none."""
        words = list(re.finditer(r"[^\W_]+", text))
        keys = [sound(w.group()) for w in words]
        for phrase in self.phrases:
            n = len(phrase)
            for start in range(min(MAX_LEADING_WORDS, len(words) - n) + 1):
                if all(_alike(keys[start + i], phrase[i], words[start + i].group()) for i in range(n)):
                    return text[words[start + n - 1].end() :].lstrip(" ,.;:!?…-–—").strip()
        return None
