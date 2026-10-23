"""Command line: `grog-voice run`, the service; `grog-voice turn WAV`, one turn
with a file as the mic, to try it out; `grog-voice listen`, what it hears."""

import argparse
import logging
import os
import sys
import threading
import time
import wave

from . import config

log = logging.getLogger("grog_voice")

# How often `grog-voice listen` asks grog its name (the default wake word),
# and how soon again when grog doesn't say (it may be starting).
NAME_EVERY_S, NAME_RETRY_S = 60.0, 5.0


def wake_words(settings, name=lambda: None):
    """What grog-voice answers to: a function giving the WakeWords, or None for
    anything heard. Without a wakeWord setting, grog's `name()` (it may
    change), and nothing while grog didn't say it (None)."""
    from .wake import WakeWords

    setting = settings["wakeWord"]
    if setting is False:
        return lambda: None
    if setting is not None:
        words = WakeWords([setting] if isinstance(setting, str) else setting)
        return lambda: words
    state = {"name": None, "words": WakeWords([])}

    def from_grog():
        if (current := name()) is not None and current != state["name"]:
            state["name"], state["words"] = current, WakeWords([current])
            log.info("answering to %s", current)
        return state["words"]

    return from_grog


class NameWatch:
    """grog's name, asked from grog (`status()`, its GET /v1/status) on a
    thread of its own every `every_s`, or `retry_s` while grog doesn't
    answer: for `grog-voice listen`, which tells grog nothing. Call it for
    the name (None before grog said)."""

    def __init__(self, status, every_s=NAME_EVERY_S, retry_s=NAME_RETRY_S):
        self._status, self._every_s, self._retry_s = status, every_s, retry_s
        self.name = None
        self._closed = threading.Event()
        threading.Thread(target=self._run, daemon=True, name="name").start()

    def __call__(self):
        return self.name

    def _run(self):
        from .grog import GrogError

        failing = False
        while True:
            try:
                name = self._status()["name"]
                if not isinstance(name, str) or not name.strip():
                    raise TypeError("no name")
            except (GrogError, KeyError, TypeError) as e:
                wait = self._retry_s
                if not failing:
                    failing = True
                    log.warning(
                        "couldn't ask grog its name, the wake word (%s): answering nothing until it says", e
                    )
            else:
                self.name, wait, failing = name, self._every_s, False
            if self._closed.wait(wait):
                return

    def close(self):
        self._closed.set()


def reporter(settings):
    """Tells grog what grog-voice is doing (see reporter.py)."""
    from . import grog
    from .reporter import Reporter

    return Reporter(
        lambda state, heard=None: grog.report(settings["grogUrl"], state, settings["conversationId"], heard)
    )


def text_to_speech(tts, engines=None):
    """The text to speech engine the `tts` settings pick, loaded. `engines`:
    its classes by name (speech.Pocket, speech.Supertonic and speech.Piper by
    default)."""
    from . import pocket, supertonic

    if engines is None:
        from .speech import Piper, Pocket, Supertonic

        engines = {"pocket": Pocket, "supertonic": Supertonic, "piper": Piper}
    engine, voice = tts["engine"], config.voice(tts)
    if engine == "pocket":
        return engines["pocket"](tts["model"], pocket.voice_file(tts["model"], voice), tts["threads"])
    speed = config.SPEEDS[engine] if tts["speed"] is None else tts["speed"]
    if engine == "piper":
        return engines["piper"](voice, speed)
    style = supertonic.voice_style(tts["model"], voice)
    return engines["supertonic"](tts["model"], style, tts["language"], tts["steps"], tts["threads"], speed)


def build(settings, open_mic, play, report=None, name=lambda: None, partials=True):
    """Loads the models (a few seconds), then opens the mic: a Voice, telling
    grog what it's doing through `report` (a Reporter, if any), and what it
    heard for grog, as it comes when grog shows it. grog's name, the wake
    word by default, comes from the Reporter, or else `name()`. With
    `partials` (and partialMs), what was said so far, while someone talks, is
    transcribed by a Parakeet of its own, in a process of its own (see
    worker.py), loaded when someone first talks."""
    from . import grog
    from .audio import NullPlayer, Player
    from .loop import Voice
    from .speech import Parakeet, SileroVad
    from .worker import Worker

    started = time.monotonic()
    model, threads = settings["stt"]["model"], settings["stt"]["threads"]
    partial_stt = Worker(Parakeet, model, threads) if partials and settings["partialMs"] else None
    stt = Parakeet(model, threads)
    tts = text_to_speech(settings["tts"])
    vad = SileroVad()
    for _ in tts("Ok."):  # the first run is slower
        pass
    log.info("models loaded in %.1f s", time.monotonic() - started)
    make_player = Player if play else NullPlayer
    if report is not None:
        report.flush()  # grog's answer has its name
    wake = wake_words(settings, (lambda: report.name) if report is not None else name)
    wake()  # says what it answers to, now
    return Voice(
        settings,
        mic=open_mic(),
        vad=vad,
        stt=stt,
        tts=tts,
        player=lambda rate, speaker: make_player(rate, speaker or settings["speaker"]),
        ask=lambda text, said=None, on_connect=None: grog.ask(
            settings["grogUrl"], settings["conversationId"], text, said, on_connect=on_connect
        ),
        wake=wake,
        report=report or (lambda state: None),
        report_heard=report.heard if report is not None else lambda text, final: None,
        captions=lambda: report is not None and report.captions,
        partial_stt=partial_stt,
    )


def tell_speakers(settings, request):
    """Answers grog's `request` for the speakers of the session (see
    speakers.py), through its POST /v1/voice/speakers."""
    from . import grog, speakers

    try:
        grog.report_speakers(settings["grogUrl"], speakers.answer(request))
    except grog.GrogError as e:
        log.warning("couldn't tell grog its speakers (%s)", e)


def run(settings):
    from . import grog
    from .audio import Mic
    from .commands import Commands

    report = reporter(settings)
    report("loading")
    # Whether the mic is on comes before it's opened: models take a while.
    commands = Commands(
        lambda: grog.commands(settings["grogUrl"]), speakers=lambda request: tell_speakers(settings, request)
    )
    voice = build(
        settings, lambda: Mic(settings["mic"], on=commands.mic_on is not False), play=True, report=report
    )
    commands.attach(voice)
    try:
        voice.run()
    finally:
        commands.close()
        voice.close()


def turn(settings, wav, play):
    """A turn, told to grog like the service's: whoever watches sees it."""
    from .audio import WavMic

    try:
        mic = WavMic(wav)
    except (OSError, ValueError, wave.Error) as e:
        print(f"Can't use {wav}: {e}", file=sys.stderr)
        return 1
    report = reporter(settings)
    report("loading")
    voice = build(settings, lambda: mic, play, report=report)
    if voice.ears.partial_stt is not None:
        # Ready, as the service's is once someone talked.
        voice.ears.partial_stt.start()
    try:
        return _turn(voice, wav)
    finally:
        voice.close()
        # Back to what the service does, most likely.
        report("listening")
        report.flush()


def _turn(voice, wav):
    from .audio import MicError
    from .mouth import where

    try:
        result = voice.turn()
    except MicError:
        print(f"No speech in {wav}.", file=sys.stderr)
        return 1
    except Exception as e:
        print(f"The turn failed: {where(e)}", file=sys.stderr)
        return 1
    print(f"heard: {result.heard.said}")
    if result.kind == "ignored":
        print("not for grog: it doesn't start with the wake word" if result.heard.said else "no words heard")
        return 1
    if result.kind == "woken":
        print("just the wake word")
        return 0
    print(f"asked: {result.command}")
    print(f"answer: {result.turn.answer.strip()}")
    print(f"sounds: {', '.join(result.turn.sounds)}")
    print(result.turn)
    return 0


def heard_line(ears, heard):
    """What `grog-voice listen` prints of `heard`: the text, what grog would
    make of it (see Ears.command), and how it sounded (how long, how much of
    it speech, how loud)."""
    command = ears.command(heard.said, heard.needs_name, heard.addressed, heard.anywhere)
    verdict = (
        "no words heard" if not heard.said
        else "answers anything" if ears.wake() is None
        else "not for grog" if command is None
        else "just the wake word" if not command
        else f"asks: {command}"
    )  # fmt: skip
    return f"{heard.said or '…'}  ->  {verdict}  ({ears.describe(heard)})"


def listen(settings):
    from . import grog
    from .audio import Mic

    watch = NameWatch(lambda: grog.status(settings["grogUrl"])) if settings["wakeWord"] is None else None
    voice = build(
        settings, lambda: Mic(settings["mic"]), play=False, name=watch or (lambda: None), partials=False
    )
    print("What it hears, whether grog would answer, and how it sounded (Ctrl-C stops):", flush=True)
    try:
        while True:
            print(heard_line(voice.ears, voice.ears.listen()), flush=True)
    finally:
        voice.close()
        if watch is not None:
            watch.close()


def main(argv=None):
    # Before anything loads OpenMP, which reads it then. The VAD's ggml runs on
    # OpenMP threads, which crawl (~80 ms per 32 ms frame) while ONNX Runtime's
    # threads spin after each decode; on one thread it takes ~2 ms, whatever
    # else runs. (The Nix package sets it too.)
    os.environ.setdefault("OMP_THREAD_LIMIT", "1")
    parser = argparse.ArgumentParser(
        prog="grog-voice", description="grog's ears and mouth: hands-free voice turns with grog."
    )
    parser.add_argument(
        "--config",
        default=os.environ.get("GROG_VOICE_CONFIG"),
        help="the settings file (JSON); default: $GROG_VOICE_CONFIG",
    )
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("run", help="listen and answer, until stopped (the service)")
    turn_parser = commands.add_parser(
        "turn",
        help="one turn with a WAV file (16 kHz, mono, 16-bit) as the mic: prints what it heard, "
        "what it asked grog, the answer and the timings",
    )
    turn_parser.add_argument("wav")
    turn_parser.add_argument("--no-play", action="store_true", help="don't play the answer")
    commands.add_parser(
        "listen",
        help="prints what the mic hears, whether grog would answer and how loud it was, without answering "
        "(to try wake words and mics)",
    )
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    try:
        settings = config.load(args.config)
    except config.ConfigError as e:
        print(e, file=sys.stderr)
        return 1
    if problems := config.problems(settings):
        print("Invalid settings:\n" + "\n".join(f"  {p}" for p in problems), file=sys.stderr)
        return 1
    try:
        if args.command == "turn":
            return turn(settings, args.wav, play=not args.no_play)
        if args.command == "listen":
            return listen(settings)
        return run(settings)
    except KeyboardInterrupt:
        return 130
