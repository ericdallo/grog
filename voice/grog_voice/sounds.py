"""Earcons: short, quiet sounds that say what grog-voice is up to without words
(which would need a language). Plucked notes, well under speech's level, all
in C major so they sound like one voice, each told apart by more than its
tune: how many notes, how high, how bright and how long they ring."""

import array
import functools
import math

VOLUME = 0.18  # about -15 dBFS; speech peaks near 0 dBFS (see speech.py)
# A timer going off is meant to be heard across the room: about -11 dBFS.
ALARM_VOLUME = 0.32


def _pluck(rate, hz, seconds, decay_s, octave, volume=VOLUME):
    """A plucked note: quick attack, exponential decay (`decay_s`, the time
    it takes to fall to 37%), `octave` of the octave above for brightness,
    faded out at the end (no click)."""
    n = int(rate * seconds)
    edge = max(1, int(rate * 0.004))
    return [
        volume
        * min(1.0, i / edge, (n - i) / edge)
        * math.exp(-i / rate / decay_s)
        * (math.sin(2 * math.pi * hz * i / rate) + octave * math.sin(4 * math.pi * hz * i / rate))
        / (1 + octave)
        for i in range(n)
    ]


def _render(rate, notes, decay_s, octave, volume=VOLUME):
    """Notes as (hz, seconds, silence after) to 16-bit PCM."""
    samples = []
    for hz, seconds, gap in notes:
        samples += _pluck(rate, hz, seconds, decay_s, octave, volume)
        samples += [0.0] * int(rate * gap)
    return array.array("h", (round(32767 * s) for s in samples)).tobytes()


@functools.cache
def listening(rate):
    """Listening: heard its name, alone or with more on the way. A soft,
    open "yes?": two mellow notes rising a fourth to the tonic (G5, C6)."""
    return _render(rate, [(784, 0.08, 0.03), (1047, 0.18, 0.0)], decay_s=0.1, octave=0.1)


@functools.cache
def ack(rate):
    """Got it, working on it: a single short, bright tick (E6), above where
    listening ends, so the two in a row climb rather than repeat."""
    return _render(rate, [(1319, 0.12, 0.0)], decay_s=0.04, octave=0.3)


@functools.cache
def back(rate):
    """Back, after grog was away (it restarted, say after an update): a rising
    arpeggio (C5, E5, G5, C6), its last note left to ring."""
    return _render(
        rate,
        [(523, 0.07, 0.015), (659, 0.07, 0.015), (784, 0.07, 0.015), (1047, 0.22, 0.0)],
        decay_s=0.08,
        octave=0.25,
    )


@functools.cache
def error(rate):
    """Couldn't answer: two low notes falling a minor third (G4, E♭4), out of
    the key the others share, ringing long enough to be heard."""
    return _render(rate, [(392, 0.12, 0.03), (311, 0.25, 0.0)], decay_s=0.1, octave=0.25)


@functools.cache
def alarm(rate):
    """A timer going off: a rising arpeggio (G5, C6, E6, G6), twice, louder
    than the others (ALARM_VOLUME) and ringing longer, to be heard across the
    room before what grog says of it."""
    run = [(784, 0.09, 0.03), (1047, 0.09, 0.03), (1319, 0.09, 0.03), (1568, 0.24, 0.2)]
    return _render(rate, run * 2, decay_s=0.12, octave=0.2, volume=ALARM_VOLUME)
