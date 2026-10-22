"""What Pocket TTS reads, when it's done speaking, which of its tries at a
short text it says, and its pauses after sentences (see speech.Pocket):
plain Python, like pocket_tts's own text handling (kyutai-labs/pocket-tts,
MIT), plus Portuguese numbers spelled out, as the model reads digits
badly."""

import itertools
import math
import os
import re

_TERMINAL = ".!?…"
_WEAK = ",;:-–—"
_CLOSERS = "\"'”’)]»"
_SENTENCE = re.compile(r"(?<=[.!?…])\s+")
_CLAUSE = re.compile(r"(?<=[,;:])\s+")


def prepare(text, rules):
    """`text` as the model reads it, and how many frames to let it go on
    after it says it's done, but for the two pocket_tts adds: pocket_tts's
    prepare_text_prompt, with the rules its export wrote (pocket.json).
    ("", 0) when there's nothing to say."""
    text = text.strip()
    if replace := rules.get("replace_characters"):
        text = " ".join(text.translate(str.maketrans(replace)).split())
        text = re.sub(r"([.!?…])\s*[,;:]", r"\1", text)
    text = " ".join(text.split())
    if not text:
        return "", 0
    if rules.get("remove_semicolons"):
        text = text.replace(";", ",")
    guess = 3 if len(text.split()) <= 4 else 1
    if rules.get("capitalize_first_letter", True) and not text[0].isupper():
        text = text[0].upper() + text[1:]
    if rules.get("append_terminal_punctuation", True):
        text = _ended(text)
    if rules.get("pad_with_spaces_for_short_inputs") and len(text.split()) < 5:
        text = " " * 8 + text
    return text, guess


def _ended(text):
    """`text` ending with a sentence mark: a trailing comma, colon or dash
    becomes a period."""
    core = text.rstrip(_CLOSERS + " ")
    closers = text[len(core) :].strip()
    if not core or core[-1] in _TERMINAL:
        return text
    if core[-1] in _WEAK:
        return core.rstrip(_WEAK + " ") + "." + closers
    return text + "."


def pieces(text, count, max_tokens):
    """`text` in pieces of at most `max_tokens` tokens (`count` counts them)
    where it can: sentences together, long ones cut at commas."""
    out, current = [], ""
    for part in _parts(text, count, max_tokens):
        joined = f"{current} {part}".strip()
        if current and count(joined) > max_tokens:
            out.append(current)
            current = part
        else:
            current = joined
    return out + [current] if current else out


def _parts(text, count, max_tokens):
    for sentence in _SENTENCE.split(text.strip()):
        if count(sentence) <= max_tokens:
            yield sentence
        else:
            yield from (clause for clause in _CLAUSE.split(sentence) if clause)


def voice_file(model, voice):
    """The file of `voice`: one in `model`'s voices/, or a .npz of its own
    (voice/tools/export_pocket.py makes them)."""
    return voice if voice.endswith(".npz") else os.path.join(model, "voices", f"{voice}.npz")


def voices(model):
    """The voices in `model`'s voices/."""
    try:
        return sorted(
            f.removesuffix(".npz") for f in os.listdir(os.path.join(model, "voices")) if f.endswith(".npz")
        )
    except OSError:
        return []


def max_frames(tokens, rules):
    """How long `tokens` of text may take to say, in frames, at most."""
    frame_rate = rules["sample_rate"] / rules["frame_samples"]
    return math.ceil((tokens / rules["tokens_per_second"] + rules["padding_seconds"]) * frame_rate)


class Ending:
    """When speech is over: `frames_after` frames after the model first says
    it's ending, which doesn't count before `min_frames` frames."""

    def __init__(self, min_frames, frames_after):
        self.min_frames, self.frames_after, self.at = min_frames, frames_after, None

    def __call__(self, frame, eos):
        """Whether frame number `frame`, which the model said is the end or
        not (`eos`), is past the end: it's dropped, and the speech is over."""
        if eos and self.at is None and frame >= self.min_frames:
            self.at = frame
        return self.at is not None and frame >= self.at + self.frames_after


# Pocket TTS says some short texts as if several voices murmured at once, or
# only murmurs them, quietly, whatever the voice (4 to 6 of 20 short
# answers; lower temperatures, more), where other noise says them well. So
# a piece of CHECK_WORDS words or fewer is made CHECK_FRAMES frames ahead
# (800 ms, most of it) before it plays, up to ATTEMPTS times while those
# sound like noise: past NOISY (speech is ~0.01; what was heard as several
# voices, 0.025), or quieter than QUIET dBFS at their loudest (a voice
# speaks at ~-18, murmurs at -40). Measured in plans/voice.md ("Breathy
# short answers").
CHECK_WORDS = 8
CHECK_FRAMES = 10
ATTEMPTS = 3
NOISY = 0.015
QUIET = -32.0


def checked(text):
    """Whether a piece is checked before it plays (see steadiest): a short
    one, where Pocket TTS murmurs."""
    return len(text.split()) <= CHECK_WORDS


def steadiest(attempts, noisiness, check=CHECK_FRAMES, limit=NOISY):
    """The frames of the first of `attempts` (iterators of frames, each made
    only once the one before it is turned down) whose first `check` frames'
    `noisiness` is `limit` at most, else of the least noisy one: the frames
    it already made, then the rest of it."""
    best = None
    for attempt in attempts:
        head = list(itertools.islice(attempt, check))
        score = noisiness(head)
        if score <= limit:
            return itertools.chain(head, attempt)
        if best is None or score < best[0]:
            best = (score, head, attempt)
    return itertools.chain(best[1], best[2]) if best else iter(())


# Sentences said in separate pieces (the first one alone, to start sooner,
# or a long text's parts) followed each other with hardly a pause, where
# Pocket TTS pauses ~0.33 s between sentences it reads together: a piece
# that ends a sentence is followed by silence, up to SENTENCE_PAUSE seconds
# of quiet (QUIET_UNDER dB or more under its loudest) at its end, played
# only before more speech (see mouth.Pauses).
SENTENCE_PAUSE = 0.4
QUIET_UNDER = 35


def ends_sentence(text):
    """Whether `text` ends with a sentence mark (closing quotes aside)."""
    core = text.rstrip(_CLOSERS + " ")
    return bool(core) and core[-1] in _TERMINAL


def pause(text, levels, window):
    """The seconds of silence to add after a piece that says `text`, its
    sound's levels given (in dB, one per `window` seconds): what's missing
    from SENTENCE_PAUSE of quiet at its end when it ends a sentence, none
    otherwise."""
    if not levels or not ends_sentence(text):
        return 0.0
    quiet = max(levels) - QUIET_UNDER
    trailing = 0
    for level in reversed(levels):
        if level >= quiet:
            break
        trailing += 1
    return max(0.0, SENTENCE_PAUSE - trailing * window)


_UNITS = (
    "zero um dois três quatro cinco seis sete oito nove dez onze doze treze catorze quinze dezesseis "
    "dezessete dezoito dezenove"
).split()
_TENS = ("", "", "vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa")
_HUNDREDS = (
    "", "cento", "duzentos", "trezentos", "quatrocentos", "quinhentos", "seiscentos", "setecentos",
    "oitocentos", "novecentos",
)  # fmt: skip
_SCALES = (("", ""), ("mil", "mil"), ("milhão", "milhões"), ("bilhão", "bilhões"))
_ORDINAL_UNITS = (
    "",
    "primeiro",
    "segundo",
    "terceiro",
    "quarto",
    "quinto",
    "sexto",
    "sétimo",
    "oitavo",
    "nono",
)
_ORDINAL_TENS = (
    "", "décimo", "vigésimo", "trigésimo", "quadragésimo", "quinquagésimo", "sexagésimo", "septuagésimo",
    "octogésimo", "nonagésimo",
)  # fmt: skip


# Nouns ending in -a that are masculine, and feminine ones that don't end so,
# among those numbers often count: they decide "um"/"uma", "duzentos"/"duzentas".
_MASCULINE_IN_A = {
    "dia", "dias", "problema", "problemas", "sistema", "sistemas", "tema", "temas", "programa", "programas",
    "clima", "planeta", "planetas", "mapa", "mapas", "idioma", "idiomas", "poema", "poemas",
}  # fmt: skip
_FEMININE = {
    "vez", "vezes", "cidade", "cidades", "noite", "noites", "tarde", "tardes", "mulher", "mulheres",
    "opção", "opções", "estação", "estações", "viagem", "viagens", "mensagem", "mensagens", "imagem",
    "imagens",
}  # fmt: skip


def _unit(n, feminine):
    return {1: "uma", 2: "duas"}.get(n, _UNITS[n]) if feminine else _UNITS[n]


def _below_thousand(n, feminine=False):
    if n == 100:
        return "cem"
    hundreds, rest = divmod(n, 100)
    words = []
    if hundreds:
        word = _HUNDREDS[hundreds]
        words.append(word[:-2] + "as" if feminine and hundreds > 1 else word)
    if rest >= 20:
        tens, units = divmod(rest, 10)
        words.append(_TENS[tens] + (f" e {_unit(units, feminine)}" if units else ""))
    elif rest:
        words.append(_unit(rest, feminine))
    return " e ".join(words)


def cardinal(n, feminine=False):
    """`n` in Brazilian Portuguese words ("dois mil e vinte e seis");
    `feminine` for "uma" and "duas"."""
    if n < 0:
        return "menos " + cardinal(-n, feminine)
    if n == 0:
        return "zero"
    if n >= 1000 ** len(_SCALES):
        return " ".join(_UNITS[int(d)] for d in str(n))
    groups, scale = [], 0  # (value, scale), from the lowest
    while n:
        n, value = divmod(n, 1000)
        if value:
            groups.append((value, scale))
        scale += 1
    words = []
    for value, scale in reversed(groups):
        if scale == 0:
            words.append(_below_thousand(value, feminine))
        elif scale == 1:
            words.append("mil" if value == 1 else f"{_below_thousand(value, feminine)} mil")
        else:
            one, many = _SCALES[scale]
            words.append(f"{_below_thousand(value)} {one if value == 1 else many}")
    lowest = groups[0][0]
    joiner = " e " if lowest < 100 or lowest % 100 == 0 else " "
    return words[0] if len(words) == 1 else " ".join(words[:-1]) + joiner + words[-1]


def ordinal(n, feminine=False):
    """`n` as an ordinal ("vigésimo primeiro", "segunda"), up to 99."""
    if not 0 < n < 100:
        return cardinal(n, feminine)
    tens, units = divmod(n, 10)
    words = [w for w in (_ORDINAL_TENS[tens], _ORDINAL_UNITS[units]) if w]
    return " ".join(w[:-1] + "a" if feminine else w for w in words)


def _integer(digits):
    return int(digits.replace(".", ""))


def _feminine(text, end):
    """Whether the word after `end` in `text` (what's counted) is feminine,
    by its ending, mostly."""
    word = m.group(1).lower() if (m := re.match(r"\s+(\w+)", text[end:])) else ""
    return word in _FEMININE or (word.endswith(("a", "as")) and word not in _MASCULINE_IN_A)


def _decimal(integer, fraction, feminine=False):
    """ "2" and "05": "dois vírgula zero cinco"."""
    zeros = len(fraction) - len(fraction.lstrip("0"))
    rest = fraction.lstrip("0")
    words = ["zero"] * zeros + ([cardinal(int(rest))] if rest else [])
    return f"{cardinal(_integer(integer), feminine)} vírgula {' '.join(words)}"


def _number(match):
    integer, fraction = match.group("int"), match.group("frac")
    feminine = _feminine(match.string, match.end())
    if fraction is not None:
        said = _decimal(integer, fraction, feminine)
    elif "." not in integer and len(integer) > 12:  # a code, a phone number: digit by digit
        said = " ".join(_UNITS[int(d)] for d in integer)
    else:
        said = cardinal(_integer(integer), feminine)
    # "10km": "dez km".
    return said + " " if match.string[match.end() : match.end() + 1].isalpha() else said


def _money(match):
    reais, cents = _integer(match.group(1)), int(match.group(2) or 0)
    words = [f"{cardinal(reais)} {'real' if reais == 1 else 'reais'}"] if reais or not cents else []
    if cents:
        words.append(f"{cardinal(cents)} {'centavo' if cents == 1 else 'centavos'}")
    return " e ".join(words)


def _time(match):
    hours, minutes = int(match.group(1)), int(match.group(2) or 0)
    said = cardinal(hours, feminine=True)
    if minutes:
        return f"{said} e {cardinal(minutes)}"
    return f"{said} {'hora' if hours == 1 else 'horas'}"


def _degrees(match):
    value = match.group(1)
    return f"{value} {'grau' if value in ('1', '-1') else 'graus'}"


# Numbers not right after a letter ("MP3" stays).
_NUMBER = r"(?<![^\W\d_])(?P<int>\d{1,3}(?:\.\d{3})+|\d+)(?:[,.](?P<frac>\d+))?"
_RULES = (
    (re.compile(r"R\$\s?(\d{1,3}(?:\.\d{3})+|\d+)(?:,(\d{2}))?"), _money),
    (re.compile(r"\b([01]?\d|2[0-3])(?:(?::|h)([0-5]\d)(?:min)?|h)\b"), _time),
    # "°", and "º" (an ordinal indicator) before a C.
    (re.compile(r"(-?\d+(?:[,.]\d+)?)\s?(?:°(?:\s?C\b)?|º\s?C\b)"), _degrees),
    (re.compile(r"(\d+(?:[,.]\d+)?)\s?%"), lambda m: f"{m.group(1)} por cento"),
    (re.compile(r"\b(\d{1,2})\s?([ºª])"), lambda m: ordinal(int(m.group(1)), m.group(2) == "ª")),
    (re.compile(r"(?:(?<=\s)|^)-(?=\d)"), lambda m: "menos "),
    (re.compile(_NUMBER), _number),
)


def spell_numbers(text):
    """`text` with its numbers in Brazilian Portuguese words: money (R$),
    times, degrees, percentages, ordinals, decimals and the rest."""
    for pattern, spell in _RULES:
        text = pattern.sub(spell, text)
    return text
