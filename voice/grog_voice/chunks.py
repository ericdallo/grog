"""Cuts an answer, as it streams in, into chunks to speak: sentences, and a
first chunk that may end at a clause, so that speaking starts sooner (text to
speech synthesizes a chunk at a time: on grognix, Supertonic takes ~0.5 s per
second of speech, Piper ~0.2)."""

import re

# Punctuation followed by whitespace: "2.5" and "..." mid-word don't end one.
SENTENCE_END = re.compile(r"[.!?…]+[\"')\]»”]*(?=\s)")
CLAUSE_END = re.compile(r"[,;:–—](?=\s)")
# The first chunk may end at a clause this many characters in.
FIRST_CLAUSE_CHARS = 15


class Chunker:
    def __init__(self):
        self.buf = ""
        self.first = True

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
        m = SENTENCE_END.search(self.buf)
        cut = m.end() if m else None
        if self.first:
            clause = next((c for c in CLAUSE_END.finditer(self.buf) if c.end() >= FIRST_CLAUSE_CHARS), None)
            if clause and (cut is None or clause.end() < cut):
                cut = clause.end()
        return cut
