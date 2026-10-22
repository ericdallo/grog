"""Cuts an answer, as it streams in, into chunks to speak: sentences, and a
first chunk that may end at a clause, so that speaking starts sooner (text to
speech synthesizes a chunk at a time: on grognix, Supertonic takes ~0.5 s per
second of speech, Piper ~0.2). Text to speech whose speech plays as it's made
(Pocket TTS) starts soon anyway: it gets whole sentences, as it would say a
clause as a sentence of its own, and a short first one with the next, as it
says a word or two alone badly."""

import re

# Punctuation followed by whitespace: "2.5" and "..." mid-word don't end one.
SENTENCE_END = re.compile(r"[.!?…]+[\"')\]»”]*(?=\s)")
CLAUSE_END = re.compile(r"[,;:–—](?=\s)")
# The first chunk may end at a clause this many characters in.
FIRST_CLAUSE_CHARS = 15
# For text to speech that streams, a first sentence of this many words or
# fewer waits for the next (pocket_tts's own bound for short texts).
SHORT_WORDS = 4


class Chunker:
    """`streams`: whether the text to speech's speech plays as it's made."""

    def __init__(self, streams=False):
        self.buf = ""
        self.first = True
        self.streams = streams

    def add(self, text):
        """The chunks that `text` completes."""
        self.buf += text
        chunks = []
        while (cut := self._cut()) is not None:
            chunk, self.buf = self.buf[:cut].strip(), self.buf[cut:]
            if chunk:
                chunks.append(chunk)
                self.first = False
        return chunks

    def flush(self):
        """What's left, once the answer is over, or pauses (while grog uses a
        tool). Speaking starts afresh after it: the next chunk may end at a
        clause too."""
        chunk, self.buf = self.buf.strip(), ""
        self.first = True
        return [chunk] if chunk else []

    def _cut(self):
        ends = SENTENCE_END.finditer(self.buf)
        m = next(ends, None)
        cut = m.end() if m else None
        if not self.first:
            return cut
        if self.streams:
            if cut is None or len(self.buf[:cut].split()) > SHORT_WORDS:
                return cut
            after = next(ends, None)
            return after.end() if after else None
        clause = next((c for c in CLAUSE_END.finditer(self.buf) if c.end() >= FIRST_CLAUSE_CHARS), None)
        if clause and (cut is None or clause.end() < cut):
            cut = clause.end()
        return cut
