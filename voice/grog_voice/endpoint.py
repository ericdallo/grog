"""Endpointing: tells utterances apart, from the speech probability a voice
activity detector gives each audio frame."""

from collections import deque
from dataclasses import dataclass

from .audio import duration


@dataclass(frozen=True)
class Pause:
    """The speaker paused: `audio` is what they said so far. If the pause
    turns out to be the end, the End's audio is the same."""

    audio: bytes


@dataclass(frozen=True)
class Resume:
    """Speech went on after a Pause."""


@dataclass(frozen=True)
class End:
    """The utterance is over: its `audio`, the `silence_s` since speech ended,
    and the `speech_s` in it (voiced frames)."""

    audio: bytes
    silence_s: float
    speech_s: float = 0.0


class Endpointer:
    """Feed it every frame with its speech probability (push), it says when an
    utterance pauses, resumes and ends. An utterance starts after a few voiced
    frames in a row, keeping `preroll_s` of audio from before them; it ends
    after `end_silence_s` of silence, keeping `tail_s` of it, or at `max_s`.
    Utterances with less than `min_speech_s` of speech, like a cough or a
    click, are dropped. Ones with less than `short_speech_s` of speech so far,
    like just a wake word, end after `short_end_silence_s` instead: people
    pause after it ("Grog... what time is it?")."""

    def __init__(
        self,
        frame_bytes,
        frame_s,
        *,
        end_silence_s,
        speculate_s,
        max_s,
        min_speech_s=0.2,
        short_speech_s=0.8,
        short_end_silence_s=1.2,
        start_prob=0.5,
        end_prob=0.35,
        start_frames=3,
        preroll_s=0.32,
        tail_s=0.2,
    ):
        self.frame_bytes, self.frame_s = frame_bytes, frame_s
        self.end_silence_s, self.speculate_s, self.min_speech_s = end_silence_s, speculate_s, min_speech_s
        self.short_speech_s = short_speech_s
        self.short_end_silence_s = max(end_silence_s, short_end_silence_s)
        self.start_prob, self.end_prob, self.start_frames = start_prob, end_prob, start_frames
        self.max_bytes = round(max_s / frame_s) * frame_bytes
        self.tail_bytes = round(tail_s / frame_s) * frame_bytes
        self.preroll = deque(maxlen=start_frames + round(preroll_s / frame_s))
        self.reset()

    def reset(self):
        self.preroll.clear()
        self.speech = None  # the utterance so far, once started
        self.voiced = 0  # voiced frames in a row (before the start), or in all (after)
        self.silent = 0  # silent frames in a row
        self.paused = False

    @property
    def started(self):
        """Whether an utterance is under way."""
        return self.speech is not None

    @property
    def talking(self):
        """Whether someone is talking right now: an utterance under way, and
        not at a pause."""
        return self.speech is not None and self.silent * self.frame_s < max(self.speculate_s, self.frame_s)

    @property
    def seconds(self):
        """How long the utterance under way is so far (0 before one starts)."""
        return duration(self.speech, self.frame_bytes, self.frame_s) if self.speech is not None else 0.0

    @property
    def size(self):
        """How long the utterance under way is so far, in bytes (0 before one
        starts)."""
        return len(self.speech) if self.speech is not None else 0

    def audio(self):
        """The utterance under way so far (b"" before one starts)."""
        return bytes(self.speech) if self.speech is not None else b""

    def drop(self, n):
        """Lets go of the first `n` bytes of the utterance under way (whole
        frames): it goes on from there, and its maximum length counts from
        there too."""
        if self.speech is not None:
            del self.speech[: n - n % self.frame_bytes]

    def push(self, frame, prob):
        """Pause, Resume, End or None."""
        if self.speech is None:
            self.preroll.append(frame)
            self.voiced = self.voiced + 1 if prob >= self.start_prob else 0
            if self.voiced >= self.start_frames:
                self.speech = bytearray(b"".join(self.preroll))
                self.silent = 0
            return None
        self.speech += frame
        if prob < self.end_prob:
            self.silent += 1
        else:
            self.silent = 0
            self.voiced += 1
            if self.paused:
                self.paused = False
                return Resume()
        silence_s, speech_s = self.silent * self.frame_s, self.voiced * self.frame_s
        cut = len(self.speech) - self.silent * self.frame_bytes + self.tail_bytes
        end_s = self.end_silence_s if speech_s >= self.short_speech_s else self.short_end_silence_s
        if silence_s >= end_s or len(self.speech) >= self.max_bytes:
            audio = bytes(self.speech[:cut])
            self.reset()
            return End(audio, silence_s, speech_s) if speech_s >= self.min_speech_s else None
        if self.speculate_s and not self.paused and silence_s >= self.speculate_s:
            self.paused = True
            return Pause(bytes(self.speech[:cut]))
        return None
