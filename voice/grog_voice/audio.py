"""Audio through PipeWire's pw-cat: 16 kHz mono 16-bit frames from the mic,
raw 16-bit mono audio to the speaker. Plus a WAV file standing in for the mic,
for `grog-voice turn`."""

import array
import logging
import math
import queue
import subprocess
import threading
import time
import wave

log = logging.getLogger("grog_voice")

RATE = 16000
FRAME_SAMPLES = 512  # what Silero VAD takes: 32 ms
FRAME_BYTES = FRAME_SAMPLES * 2
FRAME_S = FRAME_SAMPLES / RATE


class MicError(Exception):
    """The mic's input, a file, ended."""


def duration(pcm, frame_bytes=FRAME_BYTES, frame_s=FRAME_S):
    """How long `pcm` lasts, in seconds, in frames of `frame_bytes` lasting `frame_s`."""
    return len(pcm) / frame_bytes * frame_s


def _pw_cat(mode, rate, latency, target):
    """pw-cat's arguments to record or play (`mode`) raw 16-bit mono audio on
    `target`, or the default node."""
    raw = ["--raw", "--rate", str(rate), "--channels", "1", "--format", "s16", "--latency", latency]
    return ["pw-cat", mode, *raw, *(["--target", target] if target else []), "-"]


class Mic:
    """Frames from a PipeWire source (`target`, or the default one), read on a
    thread so that PipeWire never waits on us. Without a mic to record from
    (unplugged, say), it tries again, less and less often, up to every
    `retry_max_s`: plugging one in is enough, and the models stay loaded.
    What it watches (see watch) hears when there's none, and when it's back.
    Switched off (`on`, switch), it records nothing at all."""

    def __init__(self, target=None, *, argv=None, retry_min_s=1.0, retry_max_s=30.0, on=True):
        self.argv = argv or _pw_cat("--record", RATE, "32ms", target)
        self.retry_min_s, self.retry_max_s = retry_min_s, retry_max_s
        self.frames = queue.Queue()
        self.proc = None
        self.closed = threading.Event()
        self._lock = threading.Lock()  # what watches it, and whether there's a mic
        self._report, self._failing = (lambda state: None), False
        self._on = threading.Event()
        if on:
            self._on.set()
        self._switching = threading.Lock()  # whether it's on, and pw-cat starting
        threading.Thread(target=self._record, daemon=True, name="mic").start()

    def switch(self, on):
        """Records again (`on`), dropping what it heard before, or stops
        recording: pw-cat goes, until it's switched on."""
        with self._switching:
            if on:
                self.flush()
                self._on.set()
                return
            self._on.clear()
            proc = self.proc
        if proc is not None and proc.poll() is None:
            proc.terminate()

    def watch(self, report):
        """Tells `report` "no-mic" when there's no mic, and "listening" when
        it's back: now, if there's none."""
        with self._lock:
            self._report = report
            if self._failing:
                report("no-mic")

    def _failed(self, failing):
        """Whether there's no mic, said when that changed."""
        with self._lock:
            if failing != self._failing:
                self._failing = failing
                self._report("no-mic" if failing else "listening")

    def _record(self):
        wait = self.retry_min_s
        while not self.closed.is_set():
            self._on.wait()
            with self._switching:
                if self.closed.is_set():
                    return
                if not self._on.is_set():
                    continue
                self.proc = subprocess.Popen(self.argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            recorded = False
            with self.proc:
                while len(frame := self.proc.stdout.read(FRAME_BYTES)) == FRAME_BYTES:
                    if not recorded:
                        recorded, wait = True, self.retry_min_s
                        if self._failing:
                            log.info("recording again")
                            self._failed(False)
                    self.frames.put((time.monotonic(), frame))
                # pw-cat says little on stderr, and only once it's done.
                reason = self.proc.stderr.read().decode(errors="replace").strip().splitlines()
            status = self.proc.returncode
            if self.closed.is_set():
                return
            if not self._on.is_set():
                # Switched off: not a mic gone.
                continue
            if not self._failing:
                log.warning(
                    "no mic to record from (pw-cat exited with %s%s): trying again until there's one",
                    status,
                    f": {reason[-1]}" if reason else "",
                )
                self._failed(True)
            if self.closed.wait(wait):
                return
            wait = min(wait * 2, self.retry_max_s)

    def read(self):
        """The next frame and when it came."""
        return self.frames.get()

    def flush(self):
        """Drops what was heard so far."""
        while True:
            try:
                self.frames.get_nowait()
            except queue.Empty:
                return

    def close(self):
        self.closed.set()
        self._on.set()
        if self.proc:
            self.proc.terminate()
            self.proc.wait()


class WavMic:
    """A WAV file (16 kHz, mono, 16-bit) as the mic, in real time, then
    silence."""

    def __init__(self, path, silence_s=3.0):
        with wave.open(path) as w:
            if (w.getframerate(), w.getnchannels(), w.getsampwidth()) != (RATE, 1, 2):
                raise ValueError(f"{path} must be 16 kHz, mono, 16-bit")
            data = w.readframes(w.getnframes())
        data += bytes(int(silence_s * RATE) * 2)
        self.frames = [data[i : i + FRAME_BYTES] for i in range(0, len(data) - FRAME_BYTES + 1, FRAME_BYTES)]
        self.i, self.start = 0, None

    def read(self):
        if self.start is None:
            self.start = time.monotonic()
        if self.i >= len(self.frames):
            raise MicError("end of the file")
        # Frame i exists once it has been "recorded".
        if (wait := self.start + (self.i + 1) * FRAME_S - time.monotonic()) > 0:
            time.sleep(wait)
        self.i += 1
        return time.monotonic(), self.frames[self.i - 1]

    def flush(self):
        pass

    def close(self):
        pass


class Player:
    """Plays raw 16-bit mono audio at `rate` as it comes, on a PipeWire sink
    (`target`, or the default one)."""

    def __init__(self, rate, target=None):
        self.rate = rate
        self.proc = subprocess.Popen(_pw_cat("--playback", rate, "50ms", target), stdin=subprocess.PIPE)

    def write(self, pcm):
        self.proc.stdin.write(pcm)
        self.proc.stdin.flush()

    def stop(self):
        """Stops playing at once: what's left isn't played, and writing
        fails."""
        self.proc.kill()

    def close(self):
        """Returns once everything was played, or pw-cat is gone (it failed)."""
        try:
            with self.proc.stdin:  # closed, whatever happens
                # pw-cat may stop before playing the very end.
                self.proc.stdin.write(bytes(self.rate // 5 * 2))
        except OSError:
            self.proc.kill()
        finally:
            self.proc.wait()


class NullPlayer:
    """Plays nothing: for `grog-voice turn --no-play`."""

    def __init__(self, rate, target=None):
        pass

    def write(self, pcm):
        pass

    def stop(self):
        pass

    def close(self):
        pass


class Silence(bytes):
    """16-bit audio of silence that's a pause in speech, after a sentence: an
    answer plays it only before more sound (see mouth.Pauses)."""


def peak_dbfs(pcm):
    """The peak level of 16-bit audio, in dBFS."""
    samples = array.array("h", pcm)
    peak = max(max(samples, default=0), -min(samples, default=0)) / 32768
    return 20 * math.log10(peak) if peak > 0 else float("-inf")


# Speech to text makes out little of quiet speech (from across the room, say),
# often nothing: it gets it louder, peaking here, but at most this much louder.
STT_PEAK_DBFS = -3.0
STT_MAX_GAIN_DB = 30.0


def stt_gain_db(peak_db):
    """How much louder speech to text gets audio peaking at `peak_db` (dBFS):
    0 when it's loud enough."""
    return min(STT_MAX_GAIN_DB, max(0.0, STT_PEAK_DBFS - peak_db))


def sounded(peak_db, speculated=False):
    """How loud something heard was, and how it was transcribed."""
    gain = stt_gain_db(peak_db)
    how = [f"{gain:.0f} dB louder"] if gain >= 1 else []
    how += ["during the pause"] if speculated else []
    return f"peak {peak_db:.0f} dBFS" + (", transcribed " + " ".join(how) if how else "")
