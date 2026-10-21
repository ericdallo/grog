"""Earcons: short, quiet sounds that say what grog-voice is up to without words
(which would need a language). Plucked notes, well under speech's level."""

import array
import functools
import math

VOLUME = 0.18  # about -15 dBFS; Piper's speech peaks near 0 dBFS


def _pluck(rate, hz, seconds, volume=VOLUME, decay_s=0.06):
    """A soft plucked note: quick attack, exponential decay, a touch of the
    octave above for warmth, faded out at the end (no click)."""
    n = int(rate * seconds)
    edge = max(1, int(rate * 0.004))
    return [
        volume
        * min(1.0, i / edge, (n - i) / edge)
        * math.exp(-i / rate / decay_s)
        * (math.sin(2 * math.pi * hz * i / rate) + 0.25 * math.sin(4 * math.pi * hz * i / rate))
        / 1.25
        for i in range(n)
    ]


def _render(rate, notes):
    """Notes as (hz, seconds, silence after) to 16-bit PCM."""
    samples = []
    for hz, seconds, gap in notes:
        samples += _pluck(rate, hz, seconds)
        samples += [0.0] * int(rate * gap)
    return array.array("h", (round(32767 * s) for s in samples)).tobytes()


@functools.cache
def ack(rate):
    """Got it, working on it: two quick rising notes (G5, B5)."""
    return _render(rate, [(784, 0.07, 0.015), (988, 0.12, 0.0)])


@functools.cache
def listening(rate):
    """Listening: heard its name, alone or at a pause before more (E5, A5,
    wider apart)."""
    return _render(rate, [(659, 0.09, 0.04), (880, 0.16, 0.0)])


@functools.cache
def searching(rate):
    """Looking something up (a tool, like web search): three soft falling
    notes (C6, A5, F5)."""
    return _render(rate, [(1047, 0.05, 0.03), (880, 0.05, 0.03), (698, 0.09, 0.0)])


@functools.cache
def error(rate):
    """Couldn't answer: two low falling notes (A4, E4)."""
    return _render(rate, [(440, 0.12, 0.03), (330, 0.22, 0.0)])
